package io.barberbook.catalog;

import io.barberbook.shared.TimeRange;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Horário semanal de um barbeiro. Dia sem intervalos = folga. */
public record WeeklySchedule(Map<DayOfWeek, List<LocalInterval>> intervals) {

    public WeeklySchedule {
        intervals = Map.copyOf(intervals);
    }

    /**
     * Intervalos de trabalho numa data local, convertidos para instantes pelo fuso da barbearia,
     * em ordem cronológica. A conversão usa o ZoneId (não um offset fixo) para funcionar com horário de verão.
     */
    public List<TimeRange> rangesOn(LocalDate date, ZoneId zone) {
        return intervals.getOrDefault(date.getDayOfWeek(), List.of()).stream()
                .sorted(Comparator.comparing(LocalInterval::start))
                .map(i -> new TimeRange(
                        date.atTime(i.start()).atZone(zone).toInstant(),
                        date.atTime(i.end()).atZone(zone).toInstant()))
                .toList();
    }
}
