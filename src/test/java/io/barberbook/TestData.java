package io.barberbook;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixtures SQL dos testes. Cada teste monta só o catálogo de que precisa. */
public final class TestData {

    private TestData() {
    }

    public static void truncateAll(JdbcClient jdbc) {
        var tables = jdbc.sql("""
                        SELECT string_agg(quote_ident(tablename), ', ')
                        FROM pg_tables
                        WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'""")
                .query(String.class).optional();
        tables.filter(t -> !t.isBlank())
                .ifPresent(t -> jdbc.sql("TRUNCATE " + t + " RESTART IDENTITY CASCADE").update());
    }

    public static UUID service(JdbcClient jdbc, String name, int minutes, boolean active) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO service (id, name, duration_minutes, price_cents, active) VALUES (?, ?, ?, 4500, ?)")
                .params(id, name, minutes, active).update();
        return id;
    }

    public static UUID barber(JdbcClient jdbc, String name, boolean active, UUID... services) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO barber (id, name, active) VALUES (?, ?, ?)").params(id, name, active).update();
        for (var s : services) {
            jdbc.sql("INSERT INTO barber_service (barber_id, service_id) VALUES (?, ?)").params(id, s).update();
        }
        return id;
    }

    public static void works(JdbcClient jdbc, UUID barber, DayOfWeek day, String start, String end) {
        jdbc.sql("INSERT INTO working_interval (barber_id, day_of_week, starts_at, ends_at) VALUES (?, ?, ?, ?)")
                .params(barber, day.getValue(), LocalTime.parse(start), LocalTime.parse(end)).update();
    }

    /** Barbeiro que trabalha todos os dias no intervalo dado (útil para testes que não ligam para o dia). */
    public static void worksEveryDay(JdbcClient jdbc, UUID barber, String start, String end) {
        for (var day : List.of(DayOfWeek.values())) {
            works(jdbc, barber, day, start, end);
        }
    }
}
