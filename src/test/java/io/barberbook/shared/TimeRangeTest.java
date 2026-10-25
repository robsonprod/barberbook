package io.barberbook.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimeRangeTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(FORTALEZA).toInstant();
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(at("2026-11-10T" + start), at("2026-11-10T" + end));
    }

    @Test
    void rejectsEmptyOrInvertedRanges() {
        assertThatThrownBy(() -> range("10:00", "10:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> range("10:30", "10:00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startingAtBuildsEndFromDuration() {
        var r = TimeRange.startingAt(at("2026-11-10T10:00"), Duration.ofMinutes(45));
        assertThat(r.end()).isEqualTo(at("2026-11-10T10:45"));
        assertThat(r.duration()).isEqualTo(Duration.ofMinutes(45));
    }

    @Nested
    class Overlap {

        @Test
        void partialOverlap() {
            assertThat(range("10:00", "10:30").overlaps(range("10:15", "10:45"))).isTrue();
        }

        @Test
        void differentDurationsOverlap() {
            // spec: held 10:00–11:00 blocks 10:30–10:45
            assertThat(range("10:00", "11:00").overlaps(range("10:30", "10:45"))).isTrue();
        }

        @Test
        void backToBackDoesNotOverlap() {
            // half-open [start, end): 10:30 end == 10:30 start is legal
            assertThat(range("10:00", "10:30").overlaps(range("10:30", "11:00"))).isFalse();
            assertThat(range("10:30", "11:00").overlaps(range("10:00", "10:30"))).isFalse();
        }

        @Test
        void disjointDoesNotOverlap() {
            assertThat(range("09:00", "09:30").overlaps(range("10:00", "10:30"))).isFalse();
        }

        @Test
        void identicalOverlaps() {
            assertThat(range("10:00", "10:30").overlaps(range("10:00", "10:30"))).isTrue();
        }
    }

    @Nested
    class Containment {

        @Test
        void fitsInsideInterval() {
            assertThat(range("09:00", "12:00").contains(range("11:15", "12:00"))).isTrue();
        }

        @Test
        void endingAfterIntervalDoesNotFit() {
            // spec: 11:30 + 45min ends after 12:00
            assertThat(range("09:00", "12:00").contains(range("11:30", "12:15"))).isFalse();
        }

        @Test
        void startingBeforeIntervalDoesNotFit() {
            assertThat(range("09:00", "12:00").contains(range("08:45", "09:15"))).isFalse();
        }
    }

    @Nested
    class Grid {

        @Test
        void quarterHoursAreOnGrid() {
            assertThat(range("10:15", "10:45").startsOnGrid(15, FORTALEZA)).isTrue();
            assertThat(range("10:00", "10:30").startsOnGrid(15, FORTALEZA)).isTrue();
        }

        @Test
        void offGridMinutesAreRejected() {
            assertThat(range("10:10", "10:40").startsOnGrid(15, FORTALEZA)).isFalse();
        }

        @Test
        void secondsAreOffGrid() {
            var start = at("2026-11-10T10:15").plusSeconds(1);
            assertThat(TimeRange.startingAt(start, Duration.ofMinutes(15)).startsOnGrid(15, FORTALEZA)).isFalse();
        }

        @Test
        void gridIsEvaluatedInLocalTimeNotUtc() {
            // Asia/Kolkata = UTC+05:30: 10:00 local is 04:30 UTC, still on a 15-minute local grid
            var kolkata = ZoneId.of("Asia/Kolkata");
            var start = LocalDateTime.parse("2026-11-10T10:00").atZone(kolkata).toInstant();
            assertThat(TimeRange.startingAt(start, Duration.ofMinutes(15)).startsOnGrid(15, kolkata)).isTrue();
            assertThat(TimeRange.startingAt(start, Duration.ofMinutes(15)).startsOnGrid(60, ZoneId.of("UTC"))).isFalse();
        }
    }
}
