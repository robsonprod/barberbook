package io.barberbook.booking;

import static io.barberbook.booking.domain.BookingStatus.CANCELLED;
import static io.barberbook.booking.domain.BookingStatus.CONFIRMED;
import static io.barberbook.booking.domain.BookingStatus.EXPIRED;
import static io.barberbook.booking.domain.BookingStatus.HELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.IntegrationTest;
import io.barberbook.TestData;
import io.barberbook.booking.application.BookingService;
import io.barberbook.booking.application.BookingService.HoldResult;
import io.barberbook.booking.domain.Customer;
import io.barberbook.booking.domain.HoldCommand;
import io.barberbook.booking.infrastructure.MaintenanceJobs;
import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookingLifecycleTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Fortaleza");

    @Autowired
    BookingService bookings;

    @Autowired
    MaintenanceJobs jobs;

    UUID haircut; // 30 min
    UUID longService; // 60 min
    UUID beard; // 15 min
    UUID carlos;
    UUID ana;
    final LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);

    @BeforeEach
    void catalog() {
        haircut = TestData.service(jdbc, "Haircut", 30, true);
        longService = TestData.service(jdbc, "Shave", 60, true);
        beard = TestData.service(jdbc, "Beard", 15, true);
        carlos = TestData.barber(jdbc, "Carlos", true, haircut, longService, beard);
        ana = TestData.barber(jdbc, "Ana", true, haircut);
        TestData.worksEveryDay(jdbc, carlos, "09:00", "18:00");
        TestData.worksEveryDay(jdbc, ana, "09:00", "18:00");
    }

    Instant at(String time) {
        return tomorrow.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }

    static Customer maria() {
        return new Customer("Maria Silva", "maria@example.com", "+5585999990000");
    }

    HoldResult hold(UUID barber, UUID service, String time) {
        return bookings.hold(UUID.randomUUID().toString(), new HoldCommand(barber, service, at(time), maria()));
    }

    /** Envelhece um hold no banco: simula que os 10 minutos já passaram. */
    void age(UUID bookingId) {
        jdbc.sql("UPDATE booking SET expires_at = now() - interval '1 second' WHERE id = ?").param(bookingId).update();
    }

    String statusOf(UUID id) {
        return jdbc.sql("SELECT status FROM booking WHERE id = ?").param(id).query(String.class).single();
    }

    long activeRowsAt(UUID barber, String time) {
        return jdbc.sql("""
                        SELECT count(*) FROM booking WHERE barber_id = ? AND starts_at = ?
                        AND (status = 'CONFIRMED' OR (status = 'HELD' AND expires_at > now()))""")
                .params(barber, at(time).atOffset(java.time.ZoneOffset.UTC)).query(Long.class).single();
    }

    static void assertProblem(Runnable action, ProblemType type) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.type()).isEqualTo(type));
    }

    @Test
    void successfulHoldExpiresInTenMinutesByDatabaseTime() {
        var b = hold(carlos, haircut, "10:00").booking();
        assertThat(b.status()).isEqualTo(HELD);
        assertThat(b.slot().end()).isEqualTo(at("10:30"));
        var dbNow = jdbc.sql("SELECT now()").query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(b.expiresAt()).isBetween(dbNow.plusSeconds(590), dbNow.plusSeconds(601));
    }

    @Nested
    class NoDoubleBooking { // task 5.5

        @Test
        void overlappingHoldIsRejected() {
            bookings.confirm(hold(carlos, haircut, "10:00").booking().id());
            assertProblem(() -> hold(carlos, haircut, "10:15"), ProblemType.SLOT_UNAVAILABLE);
        }

        @Test
        void differentDurationsOverlap() {
            hold(carlos, longService, "10:00"); // 10:00–11:00
            assertProblem(() -> hold(carlos, beard, "10:30"), ProblemType.SLOT_UNAVAILABLE);
        }

        @Test
        void backToBackSucceeds() {
            bookings.confirm(hold(carlos, haircut, "10:00").booking().id());
            assertThatCode(() -> hold(carlos, haircut, "10:30")).doesNotThrowAnyException();
        }

        @Test
        void otherBarberAtTheSameTimeSucceeds() {
            hold(carlos, haircut, "10:00");
            assertThatCode(() -> hold(ana, haircut, "10:00")).doesNotThrowAnyException();
        }

        @Test
        void failedHoldLeavesNoTrace() {
            hold(carlos, haircut, "10:00");
            var key = "retry-me";
            assertProblem(() -> bookings.hold(key, new HoldCommand(carlos, haircut, at("10:00"), maria())),
                    ProblemType.SLOT_UNAVAILABLE);
            // a chave foi desfeita junto: um retry é reavaliado
            assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key WHERE key = ?").param(key)
                    .query(Long.class).single()).isZero();
        }
    }

    @Nested
    class Expiration { // tasks 5.2 e 5.4

        @Test
        void slotIsReusableAtExpirationBeforeTheSweeperRuns() {
            var old = hold(carlos, haircut, "14:00").booking();
            age(old.id());

            var fresh = hold(carlos, haircut, "14:00").booking(); // expire-on-write libera o horário

            assertThat(fresh.status()).isEqualTo(HELD);
            assertThat(statusOf(old.id())).isEqualTo("EXPIRED");
            assertThat(activeRowsAt(carlos, "14:00")).isEqualTo(1);
        }

        @Test
        void expiredHoldReadsExpiredEvenBeforeTheSweeper() {
            var b = hold(carlos, haircut, "14:00").booking();
            age(b.id());
            var dbNow = jdbc.sql("SELECT now()").query(java.time.OffsetDateTime.class).single().toInstant();
            assertThat(bookings.get(b.id()).effectiveStatus(dbNow)).isEqualTo(EXPIRED);
        }

        @Test
        void sweeperMarksOverdueHoldsAsExpired() {
            var overdue = hold(carlos, haircut, "14:00").booking();
            var fresh = hold(carlos, haircut, "15:00").booking();
            age(overdue.id());

            assertThat(jobs.expireOverdueHolds()).isEqualTo(1);

            assertThat(statusOf(overdue.id())).isEqualTo("EXPIRED");
            assertThat(statusOf(fresh.id())).isEqualTo("HELD");
        }

        @Test
        void concurrentSweepersAreHarmless() throws Exception {
            var overdue = hold(carlos, haircut, "14:00").booking();
            age(overdue.id());

            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = CompletableFuture.supplyAsync(jobs::expireOverdueHolds, pool);
                var b = CompletableFuture.supplyAsync(jobs::expireOverdueHolds, pool);
                assertThat(a.get() + b.get()).isEqualTo(1); // só um "ganha"; o outro não encontra nada
            }
            assertThat(statusOf(overdue.id())).isEqualTo("EXPIRED");
        }
    }

    @Nested
    class ConfirmAndCancel { // task 5.3

        @Test
        void confirmInTime() {
            var confirmed = bookings.confirm(hold(carlos, haircut, "10:00").booking().id());
            assertThat(confirmed.status()).isEqualTo(CONFIRMED);
            assertThat(confirmed.expiresAt()).isNull();
        }

        @Test
        void confirmTooLate() {
            var b = hold(carlos, haircut, "10:00").booking();
            age(b.id());
            assertProblem(() -> bookings.confirm(b.id()), ProblemType.BOOKING_EXPIRED);
        }

        @Test
        void confirmAfterSweeperIsAlsoExpired() {
            var b = hold(carlos, haircut, "10:00").booking();
            age(b.id());
            jobs.expireOverdueHolds();
            assertProblem(() -> bookings.confirm(b.id()), ProblemType.BOOKING_EXPIRED);
        }

        @Test
        void confirmIsIdempotent() {
            var id = hold(carlos, haircut, "10:00").booking().id();
            assertThat(bookings.confirm(id)).isEqualTo(bookings.confirm(id));
        }

        @Test
        void cancelConfirmedFreesTheSlot() {
            var id = hold(carlos, haircut, "15:00").booking().id();
            bookings.confirm(id);

            assertThat(bookings.cancel(id).status()).isEqualTo(CANCELLED);
            assertThatCode(() -> hold(carlos, haircut, "15:00")).doesNotThrowAnyException();
        }

        @Test
        void cancelIsIdempotentAndCancelledCannotBeConfirmed() {
            var id = hold(carlos, haircut, "15:00").booking().id();
            assertThat(bookings.cancel(id)).isEqualTo(bookings.cancel(id));
            assertProblem(() -> bookings.confirm(id), ProblemType.BOOKING_STATE_CONFLICT);
        }

        @Test
        void unknownBookingIsNotFound() {
            assertProblem(() -> bookings.confirm(UUID.randomUUID()), ProblemType.NOT_FOUND);
        }
    }

    @Nested
    class Idempotency { // tasks 6.2 e 6.3

        @Test
        void retryWithSameKeyReturnsTheOriginalBooking() {
            var cmd = new HoldCommand(carlos, haircut, at("10:00"), maria());
            var first = bookings.hold("key-1", cmd);
            var retry = bookings.hold("key-1", cmd);

            assertThat(first.replayed()).isFalse();
            assertThat(retry.replayed()).isTrue();
            assertThat(retry.booking().id()).isEqualTo(first.booking().id());
            assertThat(jdbc.sql("SELECT count(*) FROM booking").query(Long.class).single()).isEqualTo(1);
        }

        @Test
        void retryWithEquivalentButDifferentlyFormattedDataIsAReplay() {
            bookings.hold("key-1", new HoldCommand(carlos, haircut, at("10:00"), maria()));
            var retry = bookings.hold("key-1", new HoldCommand(carlos, haircut, at("10:00"),
                    new Customer(" Maria Silva ", "MARIA@example.com", "+55 (85) 99999-0000")));
            assertThat(retry.replayed()).isTrue();
        }

        @Test
        void keyReusedWithDifferentContentIsRejected() {
            bookings.hold("key-1", new HoldCommand(carlos, haircut, at("10:00"), maria()));
            assertProblem(() -> bookings.hold("key-1", new HoldCommand(carlos, haircut, at("11:00"), maria())),
                    ProblemType.IDEMPOTENCY_CONFLICT);
        }

        @Test
        void concurrentRequestsWithTheSameKeyCreateOneBooking() throws Exception {
            var cmd = new HoldCommand(carlos, haircut, at("10:00"), maria());
            var start = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = CompletableFuture.supplyAsync(() -> { await(start); return bookings.hold("same", cmd); }, pool);
                var b = CompletableFuture.supplyAsync(() -> { await(start); return bookings.hold("same", cmd); }, pool);
                start.countDown();

                assertThat(a.get().booking().id()).isEqualTo(b.get().booking().id());
                assertThat(a.get().replayed() ^ b.get().replayed()).isTrue(); // exatamente um é replay
            }
            assertThat(jdbc.sql("SELECT count(*) FROM booking").query(Long.class).single()).isEqualTo(1);
        }

        @Test
        void keysOlderThanRetentionArePurged() {
            bookings.hold("old", new HoldCommand(carlos, haircut, at("10:00"), maria()));
            bookings.hold("new", new HoldCommand(carlos, haircut, at("11:00"), maria()));
            jdbc.sql("UPDATE idempotency_key SET created_at = now() - interval '49 hours' WHERE key = 'old'").update();

            assertThat(jobs.purgeIdempotencyKeys()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT key FROM idempotency_key").query(String.class).list()).containsExactly("new");
        }

        static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
