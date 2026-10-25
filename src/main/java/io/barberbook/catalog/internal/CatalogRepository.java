package io.barberbook.catalog.internal;

import io.barberbook.catalog.BarberInfo;
import io.barberbook.catalog.LocalInterval;
import io.barberbook.catalog.ServiceInfo;
import io.barberbook.catalog.WeeklySchedule;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Leitura do catálogo. Só registros ativos: inativo é como se não existisse para o cliente. */
@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ServiceInfo> activeServices() {
        return jdbc.sql("""
                        SELECT id, name, duration_minutes, price_cents, currency
                        FROM service WHERE active ORDER BY name""")
                .query((rs, n) -> new ServiceInfo(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        Duration.ofMinutes(rs.getInt("duration_minutes")),
                        BigDecimal.valueOf(rs.getInt("price_cents"), 2),
                        rs.getString("currency")))
                .list();
    }

    public Optional<ServiceInfo> activeService(UUID id) {
        return activeServices().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public List<BarberInfo> activeBarbers() {
        // Um barbeiro só "faz" serviços ativos
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Set<UUID>> services = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT b.id, b.name, s.id AS service_id
                        FROM barber b
                        LEFT JOIN barber_service bs ON bs.barber_id = b.id
                        LEFT JOIN service s ON s.id = bs.service_id AND s.active
                        WHERE b.active
                        ORDER BY b.name""")
                .query(rs -> {
                    var id = rs.getObject("id", UUID.class);
                    names.putIfAbsent(id, rs.getString("name"));
                    var set = services.computeIfAbsent(id, k -> new HashSet<>());
                    var serviceId = rs.getObject("service_id", UUID.class);
                    if (serviceId != null) {
                        set.add(serviceId);
                    }
                });
        List<BarberInfo> result = new ArrayList<>();
        names.forEach((id, name) -> result.add(new BarberInfo(id, name, services.get(id))));
        return result;
    }

    public Optional<BarberInfo> activeBarber(UUID id) {
        return activeBarbers().stream().filter(b -> b.id().equals(id)).findFirst();
    }

    public WeeklySchedule schedule(UUID barberId) {
        Map<DayOfWeek, List<LocalInterval>> byDay = new EnumMap<>(DayOfWeek.class);
        jdbc.sql("""
                        SELECT day_of_week, starts_at, ends_at
                        FROM working_interval WHERE barber_id = :barberId""")
                .param("barberId", barberId)
                .query(rs -> {
                    byDay.computeIfAbsent(DayOfWeek.of(rs.getInt("day_of_week")), d -> new ArrayList<>())
                            .add(new LocalInterval(
                                    rs.getTime("starts_at").toLocalTime(),
                                    rs.getTime("ends_at").toLocalTime()));
                });
        return new WeeklySchedule(byDay);
    }
}
