package io.barberbook.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.barberbook.shared.TimeRange;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeeklyScheduleTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    private static final LocalDate MONDAY = LocalDate.parse("2026-11-09");
    private static final LocalDate SUNDAY = LocalDate.parse("2026-11-15");

    private static LocalInterval interval(String start, String end) {
        return new LocalInterval(LocalTime.parse(start), LocalTime.parse(end));
    }

    private final WeeklySchedule splitShift = new WeeklySchedule(Map.of(
            DayOfWeek.MONDAY, List.of(interval("13:00", "18:00"), interval("09:00", "12:00"))));

    @Test
    void dayWithoutIntervalsIsADayOff() {
        assertThat(splitShift.rangesOn(SUNDAY, FORTALEZA)).isEmpty();
    }

    @Test
    void splitShiftProducesTwoRangesInChronologicalOrder() {
        var ranges = splitShift.rangesOn(MONDAY, FORTALEZA);

        assertThat(ranges).extracting(TimeRange::start).containsExactly(
                Instant.parse("2026-11-09T12:00:00Z"), // 09:00 em Fortaleza (UTC-3)
                Instant.parse("2026-11-09T16:00:00Z")); // 13:00
        assertThat(ranges).extracting(TimeRange::end).containsExactly(
                Instant.parse("2026-11-09T15:00:00Z"),
                Instant.parse("2026-11-09T21:00:00Z"));
    }

    @Test
    void lunchBreakIsNotWorkingTime() {
        var lunch = new TimeRange(Instant.parse("2026-11-09T15:00:00Z"), Instant.parse("2026-11-09T16:00:00Z"));
        assertThat(splitShift.rangesOn(MONDAY, FORTALEZA)).noneMatch(r -> r.overlaps(lunch));
    }

    @Test
    void intervalMustEndAfterItStarts() {
        assertThatThrownBy(() -> interval("12:00", "09:00")).isInstanceOf(IllegalArgumentException.class);
    }
}
