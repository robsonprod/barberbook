package io.barberbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import io.barberbook.shared.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SlotRulesTest {

    static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    static final SlotRules RULES = new SlotRules(15, 30, FORTALEZA);
    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    /** "Agora" = 10:07 do dia 2026-11-10 em Fortaleza. */
    static final Instant NOW = at(DAY, "10:07");

    static Instant at(LocalDate date, String time) {
        return LocalDateTime.of(date, java.time.LocalTime.parse(time)).atZone(FORTALEZA).toInstant();
    }

    static TimeRange range(LocalDate date, String start, String end) {
        return new TimeRange(at(date, start), at(date, end));
    }

    static TimeRange slot(LocalDate date, String start, int minutes) {
        return TimeRange.startingAt(at(date, start), Duration.ofMinutes(minutes));
    }

    static void assertValidation(Runnable action, String messagePart) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.type()).isEqualTo(ProblemType.VALIDATION);
            assertThat(e.getMessage()).containsIgnoringCase(messagePart);
        });
    }

    @Nested
    class HoldValidation {

        final LocalDate tomorrow = DAY.plusDays(1);
        final List<TimeRange> morning = List.of(range(tomorrow, "09:00", "12:00"));

        @Test
        void validHoldPasses() {
            assertThatCode(() -> RULES.validateHold(slot(tomorrow, "11:30", 30), true, morning, NOW))
                    .doesNotThrowAnyException();
        }

        @Test
        void outsideWorkingHours() {
            // spec: 11:45 + 30min passa das 12:00
            assertValidation(() -> RULES.validateHold(slot(tomorrow, "11:45", 30), true, morning, NOW),
                    "working hours");
        }

        @Test
        void dayOffHasNoWorkingHours() {
            assertValidation(() -> RULES.validateHold(slot(tomorrow, "10:00", 30), true, List.of(), NOW),
                    "working hours");
        }

        @Test
        void offGridStart() {
            assertValidation(() -> RULES.validateHold(slot(tomorrow, "10:10", 30), true, morning, NOW), "15-minute");
        }

        @Test
        void barberDoesNotPerformService() {
            assertValidation(() -> RULES.validateHold(slot(tomorrow, "10:00", 30), false, morning, NOW),
                    "does not perform");
        }

        @Test
        void startInThePast() {
            var today = List.of(range(DAY, "09:00", "12:00"));
            assertValidation(() -> RULES.validateHold(slot(DAY, "10:00", 30), true, today, NOW), "future");
        }

        @Test
        void beyondTheBookingWindow() {
            var far = DAY.plusDays(31);
            assertValidation(() -> RULES.validateHold(slot(far, "10:00", 30), true,
                    List.of(range(far, "09:00", "12:00")), NOW), "30 days");
        }

        @Test
        void lastDayOfTheWindowIsAllowed() {
            var last = DAY.plusDays(30);
            assertThatCode(() -> RULES.validateHold(slot(last, "10:00", 30), true,
                    List.of(range(last, "09:00", "12:00")), NOW)).doesNotThrowAnyException();
        }
    }

    @Nested
    class DateWindow {

        @Test
        void todayAndThirtyDaysAheadAreAccepted() {
            assertThatCode(() -> RULES.validateDate(DAY, NOW)).doesNotThrowAnyException();
            assertThatCode(() -> RULES.validateDate(DAY.plusDays(30), NOW)).doesNotThrowAnyException();
        }

        @Test
        void yesterdayAndThirtyOneDaysAheadAreRejected() {
            assertValidation(() -> RULES.validateDate(DAY.minusDays(1), NOW), "past");
            assertValidation(() -> RULES.validateDate(DAY.plusDays(31), NOW), "30 days");
        }
    }
}
