package io.barberbook.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.barberbook.IntegrationTest;
import io.barberbook.TestData;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class AvailabilityApiTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Fortaleza");

    @Autowired
    RestTestClient client;

    UUID haircut;
    UUID beard;
    UUID carlos;
    UUID ana;
    final LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);

    @BeforeEach
    void catalog() {
        haircut = TestData.service(jdbc, "Haircut", 30, true);
        beard = TestData.service(jdbc, "Beard", 15, true);
        carlos = TestData.barber(jdbc, "Carlos", true, haircut, beard);
        ana = TestData.barber(jdbc, "Ana", true, haircut);
        TestData.worksEveryDay(jdbc, carlos, "09:00", "12:00");
        TestData.worksEveryDay(jdbc, ana, "09:00", "12:00");
    }

    String query(String params) {
        return client.get().uri("/api/v1/availability?" + params).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    void book(UUID barber, String time) {
        client.post().uri("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                        {"barberId":"%s","serviceId":"%s","startAt":"%sT%s:00-03:00",
                         "customer":{"name":"Maria","email":"m@example.com","phone":"+5585999990000"}}"""
                        .formatted(barber, haircut, tomorrow, time))
                .exchange().expectStatus().isCreated();
    }

    @Test
    void offsetInTheResponse() {
        var json = query("serviceId=%s&date=%s&barberId=%s".formatted(haircut, tomorrow, carlos));
        assertThat((String) JsonPath.read(json, "$.slots[0].start")).isEqualTo(tomorrow + "T09:00:00-03:00");
        assertThat((String) JsonPath.read(json, "$.slots[0].end")).isEqualTo(tomorrow + "T09:30:00-03:00");
        assertThat((Integer) JsonPath.read(json, "$.durationMinutes")).isEqualTo(30);
    }

    @Test
    void twoBarbersOneBusy() {
        book(carlos, "10:00");

        var json = query("serviceId=%s&date=%s".formatted(haircut, tomorrow));
        List<String> barbersAt10 = JsonPath.read(json, "$.slots[?(@.start == '%sT10:00:00-03:00')].barberIds[*]"
                .formatted(tomorrow));
        assertThat(barbersAt10).containsExactly(ana.toString());
    }

    @Test
    void existingBookingBlocksOverlappingStartsForThatBarber() {
        book(carlos, "10:00");
        var json = query("serviceId=%s&date=%s&barberId=%s".formatted(haircut, tomorrow, carlos));
        List<String> starts = JsonPath.read(json, "$.slots[*].start");
        assertThat(starts).contains(tomorrow + "T09:30:00-03:00", tomorrow + "T10:30:00-03:00")
                .doesNotContain(tomorrow + "T09:45:00-03:00", tomorrow + "T10:00:00-03:00", tomorrow + "T10:15:00-03:00");
    }

    @Test
    void expiredHoldDoesNotBlock() {
        book(carlos, "10:00");
        jdbc.sql("UPDATE booking SET expires_at = now() - interval '1 second'").update();
        var json = query("serviceId=%s&date=%s&barberId=%s".formatted(haircut, tomorrow, carlos));
        List<String> starts = JsonPath.read(json, "$.slots[*].start");
        assertThat(starts).contains(tomorrow + "T10:00:00-03:00");
    }

    @Test
    void tooFarAheadIsAValidationError() {
        client.get().uri("/api/v1/availability?serviceId={s}&date={d}", haircut, LocalDate.now(ZONE).plusDays(31))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("validation");
    }

    @Test
    void barberWhoDoesNotPerformTheServiceIsAValidationError() {
        client.get().uri("/api/v1/availability?serviceId={s}&date={d}&barberId={b}", beard, tomorrow, ana)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("The barber does not perform this service");
    }

    @Test
    void unknownServiceIsNotFound() {
        var unknown = UUID.randomUUID();
        client.get().uri("/api/v1/availability?serviceId={s}&date={d}", unknown, tomorrow)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").value(d -> assertThat((String) d).contains(unknown.toString()));
    }

    @Test
    void malformedDateIsAValidationError() {
        client.get().uri("/api/v1/availability?serviceId={s}&date=10-11-2026", haircut)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
