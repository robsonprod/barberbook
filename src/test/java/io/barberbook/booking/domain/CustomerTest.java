package io.barberbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTest {

    @Test
    void normalizesWhitespaceAndPhoneFormatting() {
        var c = new Customer("  Maria Silva ", " maria@example.com ", "+55 (85) 99999-0000");
        assertThat(c.name()).isEqualTo("Maria Silva");
        assertThat(c.email()).isEqualTo("maria@example.com");
        assertThat(c.phone()).isEqualTo("+5585999990000");
    }

    @Test
    void invalidEmailNamesTheField() {
        // spec: "Invalid email" — erro de validação nomeando o campo
        assertThatThrownBy(() -> new Customer("Maria", "not-an-email", "+5585999990000"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("customer.email"));
    }

    @Test
    void reportsEveryInvalidField() {
        assertThatThrownBy(() -> new Customer("", "x", "123"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.fieldErrors())
                        .containsOnlyKeys("customer.name", "customer.email", "customer.phone"));
    }

    // task 4.3: dados pessoais nunca aparecem em toString()
    @Test
    void toStringNeverExposesPersonalData() {
        var customer = new Customer("Maria Silva", "maria@example.com", "+5585999990000");
        var booking = Booking.hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                io.barberbook.shared.TimeRange.startingAt(Instant.parse("2026-11-11T13:00:00Z"), Duration.ofMinutes(30)),
                customer, Instant.parse("2026-11-10T12:00:00Z"), Duration.ofMinutes(10));

        for (var text : new String[] {customer.toString(), booking.toString()}) {
            assertThat(text).doesNotContain("Maria", "maria@example.com", "5585999990000");
        }
    }
}
