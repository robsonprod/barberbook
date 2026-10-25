package io.barberbook.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.IntegrationTest;
import io.barberbook.TestData;
import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class CatalogApiTest extends IntegrationTest {

    @Autowired
    RestTestClient client;

    @Autowired
    CatalogService catalog;

    @Test
    void migrationsRanAndTestProfileHasNoDemoData() {
        // task 3.1: o seed só existe no perfil local
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single())
                .isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM barber").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE script LIKE 'R__%'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void listsOnlyActiveServicesWithDurationAndPrice() {
        TestData.service(jdbc, "Haircut", 30, true);
        TestData.service(jdbc, "Kids cut", 30, false);

        client.get().uri("/api/v1/services").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("Haircut")
                .jsonPath("$[0].durationMinutes").isEqualTo(30)
                .jsonPath("$[0].price").isEqualTo(45.00)
                .jsonPath("$[0].currency").isEqualTo("BRL");
    }

    @Test
    void inactiveBarberIsNotListed() {
        var cut = TestData.service(jdbc, "Haircut", 30, true);
        var paused = TestData.service(jdbc, "Kids cut", 30, false);
        var carlos = TestData.barber(jdbc, "Carlos", true, cut, paused);
        TestData.barber(jdbc, "Pedro", false, cut);

        client.get().uri("/api/v1/barbers").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(carlos.toString())
                // serviço inativo não aparece como oferecido
                .jsonPath("$[0].serviceIds.length()").isEqualTo(1)
                .jsonPath("$[0].serviceIds[0]").isEqualTo(cut.toString());
    }

    @Test
    void unknownOrInactiveReferencesAreNotFound() {
        var paused = TestData.service(jdbc, "Kids cut", 30, false);
        var pedro = TestData.barber(jdbc, "Pedro", false);
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> catalog.service(unknown))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.type()).isEqualTo(ProblemType.NOT_FOUND);
                    assertThat(e.getMessage()).contains(unknown.toString());
                });
        assertThatThrownBy(() -> catalog.service(paused)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> catalog.barber(pedro)).isInstanceOf(DomainException.class);
    }

    @Test
    void durationOffTheGridIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> TestData.service(jdbc, "Weird", 20, true))
                .hasMessageContaining("duration_minutes");
    }
}
