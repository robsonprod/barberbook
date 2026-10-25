package io.barberbook.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.barberbook.IntegrationTest;
import io.barberbook.TestData;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
class BookingApiTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Fortaleza");

    @Autowired
    RestTestClient client;

    UUID haircut;
    UUID carlos;
    final LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);

    @BeforeEach
    void catalog() {
        haircut = TestData.service(jdbc, "Haircut", 30, true);
        carlos = TestData.barber(jdbc, "Carlos", true, haircut);
        TestData.worksEveryDay(jdbc, carlos, "09:00", "18:00");
    }

    String holdBody(String time) {
        return """
                {"barberId":"%s","serviceId":"%s","startAt":"%sT%s:00-03:00",
                 "customer":{"name":"Maria Silva","email":"maria@example.com","phone":"+5585999990000"}}"""
                .formatted(carlos, haircut, tomorrow, time);
    }

    RestTestClient.ResponseSpec post(String key, String body) {
        var spec = client.post().uri("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        return spec.body(body).exchange();
    }

    String holdId(String time) {
        var json = post(UUID.randomUUID().toString(), holdBody(time))
                .expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(json, "$.id");
    }

    @Test
    void successfulHold() {
        post("k1", holdBody("10:00"))
                .expectStatus().isCreated()
                .expectHeader().valueMatches("Location", "/api/v1/bookings/[0-9a-f-]{36}")
                .expectBody()
                .jsonPath("$.status").isEqualTo("HELD")
                .jsonPath("$.startAt").isEqualTo(tomorrow + "T10:00:00-03:00")
                .jsonPath("$.endAt").isEqualTo(tomorrow + "T10:30:00-03:00")
                .jsonPath("$.expiresAt").exists()
                .jsonPath("$.customer.email").isEqualTo("maria@example.com");
    }

    @Test
    void readAfterHold() {
        var id = holdId("10:00");
        client.get().uri("/api/v1/bookings/{id}", id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.status").isEqualTo("HELD")
                .jsonPath("$.expiresAt").exists();
    }

    @Test
    void readUnknownBookingIsNotFound() {
        client.get().uri("/api/v1/bookings/{id}", UUID.randomUUID()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("not-found");
    }

    @Test
    void confirmAndCancelOverHttp() {
        var id = holdId("10:00");
        client.post().uri("/api/v1/bookings/{id}/confirm", id).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CONFIRMED").jsonPath("$.expiresAt").doesNotExist();
        client.post().uri("/api/v1/bookings/{id}/cancel", id).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void confirmTooLateIsAConflict() {
        var id = holdId("10:00");
        jdbc.sql("UPDATE booking SET expires_at = now() - interval '1 second' WHERE id = ?::uuid").param(id).update();
        client.post().uri("/api/v1/bookings/{id}/confirm", id).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("booking-expired");
        // e a leitura já mostra EXPIRED, antes do sweeper
        client.get().uri("/api/v1/bookings/{id}", id).exchange()
                .expectBody().jsonPath("$.status").isEqualTo("EXPIRED").jsonPath("$.expiresAt").doesNotExist();
    }

    @Test
    void overlappingHoldIsSlotUnavailable() {
        holdId("10:00");
        post("k2", holdBody("10:15"))
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("slot-unavailable");
    }

    @Test
    void validationErrorsNameTheFields() {
        var body = holdBody("10:10").replace("maria@example.com", "not-an-email");
        post("k3", body).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors['customer.email']").exists();
        post("k4", holdBody("10:10")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(d -> assertThat((String) d).contains("15-minute"));
    }

    // --- idempotência pela API (tasks 6.1 e 6.4) ---

    @Test
    void retryReturnsTheSameBookingWith200() {
        var first = post("same-key", holdBody("10:00")).expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        var retry = post("same-key", holdBody("10:00")).expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat((String) JsonPath.read(retry, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
    }

    @Test
    void jsonFieldOrderDoesNotChangeTheHash() {
        post("order-key", holdBody("10:00")).expectStatus().isCreated();
        var reordered = """
                {"customer":{"phone":"+55 85 99999-0000","email":"maria@example.com","name":"Maria Silva"},
                 "startAt":"%sT10:00:00-03:00","serviceId":"%s","barberId":"%s"}""".formatted(tomorrow, haircut, carlos);
        post("order-key", reordered).expectStatus().isOk();
    }

    @Test
    void keyReusedWithDifferentContentIs422() {
        post("reused", holdBody("10:00")).expectStatus().isCreated();
        post("reused", holdBody("11:00"))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("idempotency-conflict");
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        post(null, holdBody("10:00"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors['Idempotency-Key']").isEqualTo("is required");
    }

    // --- task 7.4: dados do cliente nunca vão para o log ---

    @Test
    void rejectedHoldLogsNoCustomerData(CapturedOutput output) {
        holdId("10:00");
        post("log-check", holdBody("10:00")).expectStatus().isEqualTo(409);

        assertThat(output.getOut()).contains("slot unavailable");
        assertThat(output.getAll()).doesNotContain("Maria Silva", "maria@example.com", "5585999990000");
    }
}
