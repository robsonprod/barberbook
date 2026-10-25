package io.barberbook.booking.application;

import io.barberbook.booking.domain.Booking;
import io.barberbook.booking.domain.HoldCommand;
import io.barberbook.booking.domain.SlotRules;
import io.barberbook.booking.infrastructure.BookingRepository;
import io.barberbook.booking.infrastructure.IdempotencyRepository;
import io.barberbook.catalog.CatalogService;
import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import io.barberbook.shared.ShopProperties;
import io.barberbook.shared.TimeRange;
import java.sql.SQLException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de reserva. Cada método é uma transação; o "agora" vem do banco. */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final String EXCLUSION_VIOLATION = "23P01";

    public record HoldResult(Booking booking, boolean replayed) {
    }

    private final BookingRepository bookings;
    private final IdempotencyRepository idempotency;
    private final CatalogService catalog;
    private final SlotRules rules;
    private final ShopProperties shop;

    BookingService(BookingRepository bookings, IdempotencyRepository idempotency, CatalogService catalog,
                   SlotRules rules, ShopProperties shop) {
        this.bookings = bookings;
        this.idempotency = idempotency;
        this.catalog = catalog;
        this.rules = rules;
        this.shop = shop;
    }

    /**
     * Segura um horário.
     * <ol>
     *   <li>Reserva a chave de idempotência (duplicados esperam aqui e recebem a reserva original);</li>
     *   <li>valida contra catálogo e expediente;</li>
     *   <li>insere — a exclusion constraint decide quem ganha uma corrida.</li>
     * </ol>
     * Qualquer falha desfaz tudo, inclusive a chave: um retry é reavaliado (o horário pode ter vagado).
     */
    @Transactional
    public HoldResult hold(String idempotencyKey, HoldCommand command) {
        var requestHash = command.canonicalHash();
        var bookingId = UUID.randomUUID();

        if (!idempotency.claim(idempotencyKey, requestHash, bookingId)) {
            var stored = idempotency.find(idempotencyKey).orElseThrow();
            if (!stored.requestHash().equals(requestHash)) {
                throw new DomainException(ProblemType.IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key was already used with a different request");
            }
            return new HoldResult(get(stored.bookingId()), true);
        }

        var service = catalog.service(command.serviceId());
        var barber = catalog.barber(command.barberId());
        var slot = TimeRange.startingAt(command.start(), service.duration());
        var now = bookings.now();
        var workingRanges = catalog.schedule(barber.id())
                .rangesOn(slot.start().atZone(rules.zone()).toLocalDate(), rules.zone());
        rules.validateHold(slot, barber.performs(service.id()), workingRanges, now);

        var hold = Booking.hold(bookingId, barber.id(), service.id(), slot, command.customer(), now, shop.holdDuration());
        try {
            return new HoldResult(bookings.insertHold(hold, shop.holdDuration()), false);
        } catch (DataIntegrityViolationException e) {
            if (EXCLUSION_VIOLATION.equals(sqlState(e))) {
                // Esperado numa corrida. Log só com dados operacionais — nunca dados do cliente.
                log.info("Hold rejected, slot unavailable: barber={} start={}", barber.id(), slot.start());
                throw new DomainException(ProblemType.SLOT_UNAVAILABLE,
                        "The barber already has a booking that overlaps "
                                + slot.start().atZone(rules.zone()).toOffsetDateTime()); // hora local, não UTC
            }
            throw e;
        }
    }

    /** Hora do banco, para calcular o status efetivo exibido ao cliente. */
    @Transactional(readOnly = true)
    public java.time.Instant now() {
        return bookings.now();
    }

    @Transactional(readOnly = true)
    public Booking get(UUID id) {
        return bookings.find(id).orElseThrow(() -> DomainException.notFound("Booking " + id + " not found"));
    }

    /** O domínio decide se pode; o UPDATE condicional garante que ninguém mudou o estado no meio. */
    @Transactional
    public Booking confirm(UUID id) {
        var current = get(id);
        var now = bookings.now(); // constante dentro da transação
        var target = current.confirm(now);
        if (target.equals(current) || bookings.confirm(id)) {
            return get(id);
        }
        // 0 linhas: outra transação mudou o estado (sweeper, cancelamento). Reavalia com o estado novo.
        return get(id).confirm(now);
    }

    @Transactional
    public Booking cancel(UUID id) {
        var current = get(id);
        var now = bookings.now();
        var target = current.cancel(now);
        if (target.equals(current) || bookings.cancel(id)) {
            return get(id);
        }
        return get(id).cancel(now);
    }

    private static String sqlState(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e.getSQLState();
            }
        }
        return null;
    }
}
