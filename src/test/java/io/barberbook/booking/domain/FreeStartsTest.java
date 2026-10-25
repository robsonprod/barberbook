package io.barberbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.barberbook.shared.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** task 7.2: cálculo de horários livres, cenário a cenário da spec slot-availability. */
class FreeStartsTest {

    static final ZoneId ZONE = ZoneId.of("America/Fortaleza");
    static final SlotRules RULES = new SlotRules(15, 30, ZONE);
    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    static final Instant YESTERDAY = DAY.minusDays(1).atStartOfDay(ZONE).toInstant();

    static Instant at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }

    static TimeRange range(String start, String end) {
        return new TimeRange(at(start), at(end));
    }

    static List<String> localTimes(List<Instant> starts) {
        return starts.stream().map(i -> i.atZone(ZONE).toLocalTime().toString()).toList();
    }

    @Test
    void slotMustFitTheWorkingInterval() {
        // spec: expediente 09:00–12:00, serviço de 45 min → 11:15 sim, 11:30 não
        var starts = localTimes(RULES.freeStarts(Duration.ofMinutes(45), List.of(range("09:00", "12:00")), List.of(), YESTERDAY));
        assertThat(starts).contains("09:00", "11:15").doesNotContain("11:30", "11:45");
        assertThat(starts).hasSize(10); // 09:00 ... 11:15 de 15 em 15
    }

    @Test
    void existingBookingBlocksOverlappingStarts() {
        // spec: reserva 10:00–10:30, serviço de 30 min → 09:45, 10:00, 10:15 bloqueados; 09:30 e 10:30 livres
        var starts = localTimes(RULES.freeStarts(Duration.ofMinutes(30), List.of(range("09:00", "12:00")),
                List.of(range("10:00", "10:30")), YESTERDAY));
        assertThat(starts).contains("09:30", "10:30").doesNotContain("09:45", "10:00", "10:15");
    }

    @Test
    void splitShiftSkipsTheLunchBreak() {
        var starts = localTimes(RULES.freeStarts(Duration.ofMinutes(30),
                List.of(range("09:00", "12:00"), range("13:00", "14:00")), List.of(), YESTERDAY));
        assertThat(starts).contains("11:30", "13:00", "13:30").doesNotContain("11:45", "12:00", "12:30");
    }

    @Test
    void pastTimesAreNotOffered() {
        // spec: hoje às 10:07 → nada às 10:07 ou antes
        var starts = localTimes(RULES.freeStarts(Duration.ofMinutes(15), List.of(range("09:00", "12:00")),
                List.of(), at("10:07")));
        assertThat(starts).first().isEqualTo("10:15");
    }

    @Test
    void startExactlyNowIsNotOffered() {
        var starts = localTimes(RULES.freeStarts(Duration.ofMinutes(15), List.of(range("09:00", "12:00")),
                List.of(), at("10:00")));
        assertThat(starts).first().isEqualTo("10:15");
    }

    @Test
    void dayOffHasNoStarts() {
        assertThat(RULES.freeStarts(Duration.ofMinutes(30), List.of(), List.of(), YESTERDAY)).isEmpty();
    }

    @Test
    void intervalShorterThanTheServiceHasNoStarts() {
        assertThat(RULES.freeStarts(Duration.ofMinutes(60), List.of(range("09:00", "09:45")), List.of(), YESTERDAY))
                .isEmpty();
    }
}
