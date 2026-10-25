package io.barberbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/** task 7.5: o contrato publicado cobre todos os endpoints e tipos de erro. */
@AutoConfigureRestTestClient
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiDocumentTest extends IntegrationTest {

    @Autowired
    RestTestClient client;

    @Test
    void documentsEveryEndpointAndErrorStatus() {
        var json = client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        Map<String, Object> paths = JsonPath.read(json, "$.paths");
        assertThat(paths).containsKeys(
                "/api/v1/services", "/api/v1/barbers", "/api/v1/availability",
                "/api/v1/bookings", "/api/v1/bookings/{id}",
                "/api/v1/bookings/{id}/confirm", "/api/v1/bookings/{id}/cancel");

        Map<String, Object> holdResponses = JsonPath.read(json, "$.paths['/api/v1/bookings'].post.responses");
        assertThat(holdResponses).containsKeys("201", "200", "400", "404", "409", "422");
        assertThat(json).contains("Idempotency-Key");
    }
}
