package io.barberbook.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.IntegrationTest;
import io.barberbook.TestData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prova que a regra "sem sobreposição" é do BANCO: insere direto via SQL, sem passar pela aplicação.
 */
class NoOverlapConstraintTest extends IntegrationTest {

    UUID barber;
    UUID otherBarber;
    UUID service;

    @BeforeEach
    void catalog() {
        service = TestData.service(jdbc, "Haircut", 30, true);
        barber = TestData.barber(jdbc, "Carlos", true, service);
        otherBarber = TestData.barber(jdbc, "Ana", true, service);
    }

    void insert(UUID barberId, String start, String end, String status) {
        jdbc.sql("""
                        INSERT INTO booking (id, barber_id, service_id, starts_at, ends_at, status, expires_at,
                                             customer_name, customer_email, customer_phone)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'x', 'x@x.io', '+5585999990000')""")
                .params(UUID.randomUUID(), barberId, service, ts(start), ts(end), status,
                        status.equals("HELD") ? ts("23:59") : null)
                .update();
    }

    static OffsetDateTime ts(String time) {
        return OffsetDateTime.parse("2026-11-10T" + time + ":00Z").withOffsetSameInstant(ZoneOffset.UTC);
    }

    static String sqlState(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e.getSQLState();
            }
        }
        return null;
    }

    @Test
    void overlappingActiveBookingsAreRejectedWithExclusionViolation() {
        insert(barber, "13:00", "13:30", "CONFIRMED");

        assertThatThrownBy(() -> insert(barber, "13:15", "13:45", "HELD"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01")); // exclusion_violation
    }

    @Test
    void differentDurationsInsideAnotherBookingAreRejected() {
        insert(barber, "13:00", "14:00", "HELD");
        assertThatThrownBy(() -> insert(barber, "13:30", "13:45", "CONFIRMED"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
    }

    @Test
    void backToBackBookingsAreAllowed() {
        insert(barber, "13:00", "13:30", "CONFIRMED");
        assertThatCode(() -> insert(barber, "13:30", "14:00", "CONFIRMED")).doesNotThrowAnyException();
        assertThatCode(() -> insert(barber, "12:30", "13:00", "HELD")).doesNotThrowAnyException();
    }

    @Test
    void otherBarberAtTheSameTimeIsAllowed() {
        insert(barber, "13:00", "13:30", "CONFIRMED");
        assertThatCode(() -> insert(otherBarber, "13:00", "13:30", "CONFIRMED")).doesNotThrowAnyException();
    }

    @Test
    void cancelledAndExpiredBookingsDoNotBlock() {
        insert(barber, "13:00", "13:30", "CANCELLED");
        insert(barber, "13:00", "13:30", "EXPIRED");
        assertThatCode(() -> insert(barber, "13:00", "13:30", "CONFIRMED")).doesNotThrowAnyException();
    }

    @Test
    void heldRowWithoutExpirationIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO booking (id, barber_id, service_id, starts_at, ends_at, status,
                                             customer_name, customer_email, customer_phone)
                        VALUES (?, ?, ?, ?, ?, 'HELD', 'x', 'x@x.io', '+5585999990000')""")
                .params(UUID.randomUUID(), barber, service, ts("13:00"), ts("13:30")).update())
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514")); // check_violation
    }
}
