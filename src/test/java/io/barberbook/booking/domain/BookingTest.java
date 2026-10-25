package io.barberbook.booking.domain;

import static io.barberbook.booking.domain.BookingStatus.CANCELLED;
import static io.barberbook.booking.domain.BookingStatus.CONFIRMED;
import static io.barberbook.booking.domain.BookingStatus.EXPIRED;
import static io.barberbook.booking.domain.BookingStatus.HELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import io.barberbook.shared.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BookingTest {

    static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    static final Duration HOLD = Duration.ofMinutes(10);
    static final TimeRange SLOT = TimeRange.startingAt(Instant.parse("2026-11-11T13:00:00Z"), Duration.ofMinutes(30));
    static final Customer CUSTOMER = new Customer("Maria Silva", "maria@example.com", "+5585999990000");

    static Booking held() {
        return Booking.hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SLOT, CUSTOMER, NOW, HOLD);
    }

    static void assertProblem(Runnable action, ProblemType type) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.type()).isEqualTo(type));
    }

    @Test
    void holdStartsHeldWithExpiration() {
        var b = held();
        assertThat(b.status()).isEqualTo(HELD);
        assertThat(b.expiresAt()).isEqualTo(NOW.plus(HOLD));
        assertThat(b.slot()).isEqualTo(SLOT);
    }

    @Nested
    class Confirm {

        @Test
        void beforeExpirationConfirmsAndClearsExpiration() {
            var b = held().confirm(NOW.plus(Duration.ofMinutes(5)));
            assertThat(b.status()).isEqualTo(CONFIRMED);
            assertThat(b.expiresAt()).isNull();
        }

        @Test
        void afterExpirationIsRejected() {
            assertProblem(() -> held().confirm(NOW.plus(Duration.ofMinutes(11))), ProblemType.BOOKING_EXPIRED);
        }

        @Test
        void exactlyAtExpirationIsRejected() {
            // expires_at é exclusivo: no instante da expiração o horário já foi liberado
            assertProblem(() -> held().confirm(NOW.plus(HOLD)), ProblemType.BOOKING_EXPIRED);
        }

        @Test
        void confirmingTwiceIsIdempotent() {
            var once = held().confirm(NOW);
            assertThat(once.confirm(NOW.plus(Duration.ofHours(1)))).isEqualTo(once);
        }

        @Test
        void cancelledCannotBeConfirmed() {
            var cancelled = held().cancel(NOW);
            assertProblem(() -> cancelled.confirm(NOW), ProblemType.BOOKING_STATE_CONFLICT);
        }

        @Test
        void expiredCannotBeConfirmed() {
            var expired = held().expire(NOW.plus(HOLD));
            assertProblem(() -> expired.confirm(NOW), ProblemType.BOOKING_EXPIRED);
        }
    }

    @Nested
    class Cancel {

        @Test
        void heldCanBeCancelled() {
            assertThat(held().cancel(NOW).status()).isEqualTo(CANCELLED);
        }

        @Test
        void confirmedCanBeCancelled() {
            assertThat(held().confirm(NOW).cancel(NOW).status()).isEqualTo(CANCELLED);
        }

        @Test
        void cancellingTwiceIsIdempotent() {
            var once = held().cancel(NOW);
            assertThat(once.cancel(NOW)).isEqualTo(once);
        }

        @Test
        void expiredCannotBeCancelled() {
            var expired = held().expire(NOW.plus(HOLD));
            assertProblem(() -> expired.cancel(NOW), ProblemType.BOOKING_EXPIRED);
        }
    }

    @Nested
    class Expiration {

        @Test
        void heldBecomesExpiredAtExpirationTime() {
            assertThat(held().expire(NOW.plus(HOLD)).status()).isEqualTo(EXPIRED);
        }

        @Test
        void heldBeforeExpirationIsUnchanged() {
            var b = held();
            assertThat(b.expire(NOW.plus(Duration.ofMinutes(9)))).isEqualTo(b);
        }

        @Test
        void confirmedNeverExpires() {
            var confirmed = held().confirm(NOW);
            assertThat(confirmed.expire(NOW.plus(Duration.ofDays(1)))).isEqualTo(confirmed);
        }
    }

    @Nested
    class Activity {

        @Test
        void heldIsActiveOnlyBeforeExpiration() {
            var b = held();
            assertThat(b.isActiveAt(NOW.plus(Duration.ofMinutes(9)))).isTrue();
            assertThat(b.isActiveAt(NOW.plus(HOLD))).isFalse();
        }

        @Test
        void effectiveStatusShowsExpiredBeforeTheSweeperRuns() {
            assertThat(held().effectiveStatus(NOW.plus(HOLD))).isEqualTo(EXPIRED);
        }

        @Test
        void confirmedIsActiveAndCancelledIsNot() {
            assertThat(held().confirm(NOW).isActiveAt(NOW.plus(Duration.ofDays(1)))).isTrue();
            assertThat(held().cancel(NOW).isActiveAt(NOW)).isFalse();
        }
    }
}
