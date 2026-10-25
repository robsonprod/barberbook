package io.barberbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Os cenários de concorrência das specs, contra DUAS instâncias reais da aplicação e o mesmo PostgreSQL.
 * Se a garantia dependesse de memória da aplicação (synchronized, cache, lock local), este teste falharia.
 */
class RaceConditionTest {

    static final ZoneId ZONE = ZoneId.of("America/Fortaleza");
    static final int CLIENTS = 20;

    static ConfigurableApplicationContext instanceA;
    static ConfigurableApplicationContext instanceB;
    static JdbcClient jdbc;
    static final HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    UUID haircut;
    UUID carlos;
    final LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);

    @BeforeAll
    static void startTwoInstances() {
        instanceA = start();
        instanceB = start();
        jdbc = instanceA.getBean(JdbcClient.class);
        // Guarda contra o bug acima: as duas instâncias PRECISAM estar no banco do Testcontainers
        for (var ctx : java.util.List.of(instanceA, instanceB)) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo(IntegrationTest.POSTGRES.getJdbcUrl());
        }
    }

    static ConfigurableApplicationContext start() {
        var pg = IntegrationTest.POSTGRES; // mesmo container dos outros testes
        // Argumentos de linha de comando, NÃO .properties(): aquelas são "default properties", de menor
        // precedência, e o application.yml (localhost:5440) as sobrescreveria — o teste iria para o banco errado.
        return new SpringApplicationBuilder(BarberBookApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + pg.getJdbcUrl(),
                        "--spring.datasource.username=" + pg.getUsername(),
                        "--spring.datasource.password=" + pg.getPassword());
    }

    @AfterAll
    static void stop() {
        instanceA.close();
        instanceB.close();
    }

    @BeforeEach
    void catalog() {
        TestData.truncateAll(jdbc);
        haircut = TestData.service(jdbc, "Haircut", 30, true);
        carlos = TestData.barber(jdbc, "Carlos", true, haircut);
        TestData.worksEveryDay(jdbc, carlos, "09:00", "18:00");
    }

    static int port(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    HttpResponse<String> hold(int port, String time, String customerSuffix) throws Exception {
        var body = """
                {"barberId":"%s","serviceId":"%s","startAt":"%sT%s:00-03:00",
                 "customer":{"name":"Client %s","email":"c%s@example.com","phone":"+5585999990000"}}"""
                .formatted(carlos, haircut, tomorrow, time, customerSuffix, customerSuffix);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()) // clientes DIFERENTES: chaves diferentes
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    long activeBookings() {
        return jdbc.sql("""
                        SELECT count(*) FROM booking
                        WHERE status = 'CONFIRMED' OR (status = 'HELD' AND expires_at > now())""")
                .query(Long.class).single();
    }

    @Test
    void raceForTheSameSlotHasExactlyOneWinner() throws Exception {
        var ports = new int[] {port(instanceA), port(instanceB)};
        var start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLIENTS; i++) {
                int client = i;
                futures.add(pool.submit(() -> {
                    start.await(); // todos largam juntos
                    return hold(ports[client % 2], "10:00", String.valueOf(client)); // metade em cada instância
                }));
            }
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (var f : futures) {
                statuses.add(f.get().statusCode());
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(CLIENTS - 1);
            assertThat(futures.stream().map(this::body).filter(b -> b.contains("slot-unavailable")))
                    .hasSize(CLIENTS - 1);
        }
        assertThat(activeBookings()).isEqualTo(1);
    }

    /**
     * Controle negativo: prova que o teste acima detectaria o bug. Sem a exclusion constraint,
     * a mesma corrida gera reservas duplicadas — porque a aplicação, de propósito, não faz
     * "SELECT para ver se está livre" antes do INSERT. A constraint é a única proteção, e basta.
     */
    @Test
    void withoutTheConstraintTheSameRaceDoubleBooks() throws Exception {
        jdbc.sql("ALTER TABLE booking DROP CONSTRAINT booking_no_overlap").update();
        try {
            var ports = new int[] {port(instanceA), port(instanceB)};
            var start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < CLIENTS; i++) {
                    int client = i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        return hold(ports[client % 2], "10:00", String.valueOf(client));
                    }));
                }
                start.countDown();
                for (var f : futures) {
                    f.get();
                }
            }
            assertThat(activeBookings()).as("double bookings without the constraint").isGreaterThan(1);
        } finally {
            TestData.truncateAll(jdbc);
            jdbc.sql("""
                    ALTER TABLE booking ADD CONSTRAINT booking_no_overlap
                        EXCLUDE USING gist (barber_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
                        WHERE (status IN ('HELD', 'CONFIRMED'))""").update();
        }
    }

    @Test
    void slotTakenBetweenQueryAndBooking() throws Exception {
        // spec slot-availability: "Availability is advisory"
        for (var instance : List.of(instanceA, instanceB)) {
            var availability = http.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + port(instance) + "/api/v1/availability?serviceId=" + haircut
                                    + "&date=" + tomorrow + "&barberId=" + carlos)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(availability).contains(tomorrow + "T16:00:00-03:00"); // os dois veem 16:00 livre
        }

        var first = hold(port(instanceA), "16:00", "a");
        var second = hold(port(instanceB), "16:00", "b");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("slot-unavailable");
        assertThat(activeBookings()).isEqualTo(1);
    }

    private String body(Future<HttpResponse<String>> f) {
        try {
            return f.get().body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
