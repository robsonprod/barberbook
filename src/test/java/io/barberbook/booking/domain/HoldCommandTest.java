package io.barberbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** task 6.1: hash canônico estável. */
class HoldCommandTest {

    static final UUID BARBER = UUID.randomUUID();
    static final UUID SERVICE = UUID.randomUUID();
    static final Instant START = Instant.parse("2026-11-11T13:00:00Z");

    static String hash(Customer c, Instant start) {
        return new HoldCommand(BARBER, SERVICE, start, c).canonicalHash();
    }

    @Test
    void sameDataDifferentFormattingGivesSameHash() {
        var a = hash(new Customer("Maria", "maria@example.com", "+5585999990000"), START);
        var b = hash(new Customer(" Maria ", "MARIA@example.com", "+55 (85) 99999-0000"), START);
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void differentContentGivesDifferentHash() {
        var c = new Customer("Maria", "maria@example.com", "+5585999990000");
        assertThat(hash(c, START)).isNotEqualTo(hash(c, START.plusSeconds(900)));
    }
}
