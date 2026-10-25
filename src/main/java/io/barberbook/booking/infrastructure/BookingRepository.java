package io.barberbook.booking.infrastructure;

import io.barberbook.booking.domain.Booking;
import io.barberbook.booking.domain.BookingStatus;
import io.barberbook.booking.domain.Customer;
import io.barberbook.shared.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL explícito das reservas. As garantias de concorrência estão nos comandos:
 * <ul>
 *   <li>{@link #insertHold}: expire-on-write + INSERT protegido pela exclusion constraint;</li>
 *   <li>{@link #confirm}/{@link #cancel}: UPDATE condicional (0 linhas = estado mudou no meio do caminho);</li>
 *   <li>o relógio é o {@code now()} do banco, o mesmo para todas as instâncias.</li>
 * </ul>
 */
@Repository
public class BookingRepository {

    private static final String COLUMNS = """
            id, barber_id, service_id, starts_at, ends_at, status, expires_at,
            customer_name, customer_email, customer_phone""";

    /** Booking "ativo" = ocupa o horário. Mesma regra do WHERE da exclusion constraint + expiração. */
    private static final String ACTIVE = "(status = 'CONFIRMED' OR (status = 'HELD' AND expires_at > now()))";

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Hora do banco. Dentro de uma transação, {@code now()} é constante: decisões coerentes. */
    public Instant now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    public Optional<Booking> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE id = :id")
                .param("id", id)
                .query(BookingRepository::map)
                .optional();
    }

    /**
     * Cria o HELD. Antes do INSERT, expira holds vencidos que se sobrepõem a este horário (expire-on-write):
     * a constraint não pode usar now(), então sem isso um hold vencido ainda não varrido bloquearia o horário.
     * A sobreposição com reservas ativas é barrada pela constraint (SQLSTATE 23P01), não por um SELECT.
     *
     * @return o booking com o {@code expires_at} calculado pelo banco
     */
    public Booking insertHold(Booking hold, Duration holdDuration) {
        jdbc.sql("""
                        UPDATE booking SET status = 'EXPIRED', updated_at = now()
                        WHERE barber_id = :barber
                          AND status = 'HELD'
                          AND expires_at <= now()
                          AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:start, :end, '[)')""")
                .param("barber", hold.barberId())
                .param("start", utc(hold.slot().start()))
                .param("end", utc(hold.slot().end()))
                .update();

        var expiresAt = jdbc.sql("""
                        INSERT INTO booking (id, barber_id, service_id, starts_at, ends_at, status, expires_at,
                                             customer_name, customer_email, customer_phone)
                        VALUES (:id, :barber, :service, :start, :end, 'HELD',
                                now() + make_interval(secs => :holdSeconds),
                                :name, :email, :phone)
                        RETURNING expires_at""")
                .param("id", hold.id())
                .param("barber", hold.barberId())
                .param("service", hold.serviceId())
                .param("start", utc(hold.slot().start()))
                .param("end", utc(hold.slot().end()))
                .param("holdSeconds", holdDuration.toSeconds())
                .param("name", hold.customer().name())
                .param("email", hold.customer().email())
                .param("phone", hold.customer().phone())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();

        return new Booking(hold.id(), hold.barberId(), hold.serviceId(), hold.slot(), hold.customer(),
                BookingStatus.HELD, expiresAt);
    }

    /** HELD → CONFIRMED só se ainda não expirou. Atômico: corre bem contra o sweeper. */
    public boolean confirm(UUID id) {
        return jdbc.sql("""
                        UPDATE booking SET status = 'CONFIRMED', expires_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'HELD' AND expires_at > now()""")
                .param("id", id)
                .update() == 1;
    }

    /** CONFIRMED ou HELD não vencido → CANCELLED. Libera o horário na hora (sai do WHERE da constraint). */
    public boolean cancel(UUID id) {
        return jdbc.sql("""
                        UPDATE booking SET status = 'CANCELLED', expires_at = NULL, updated_at = now()
                        WHERE id = :id
                          AND (status = 'CONFIRMED' OR (status = 'HELD' AND expires_at > now()))""")
                .param("id", id)
                .update() == 1;
    }

    /** Sweeper: idempotente, pode rodar em várias instâncias ao mesmo tempo sem lock. */
    public int expireOverdueHolds() {
        return jdbc.sql("""
                        UPDATE booking SET status = 'EXPIRED', updated_at = now()
                        WHERE status = 'HELD' AND expires_at <= now()""")
                .update();
    }

    /** Reservas que ocupam horário, de alguns barbeiros, numa janela de tempo. */
    public List<Booking> activeOverlapping(Collection<UUID> barberIds, TimeRange window) {
        if (barberIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE barber_id IN (:barbers) AND " + ACTIVE + """
                         AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')""")
                .param("barbers", barberIds)
                .param("from", utc(window.start()))
                .param("to", utc(window.end()))
                .query(BookingRepository::map)
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Booking map(ResultSet rs, int row) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Booking(
                rs.getObject("id", UUID.class),
                rs.getObject("barber_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                new TimeRange(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
                new Customer(rs.getString("customer_name"), rs.getString("customer_email"), rs.getString("customer_phone")),
                BookingStatus.valueOf(rs.getString("status")),
                expires == null ? null : expires.toInstant());
    }
}
