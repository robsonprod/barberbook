package io.barberbook.shared;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Intervalo de tempo semiaberto {@code [start, end)}.
 *
 * <p>Semiaberto é o que torna horários "colados" legais: 10:00–10:30 e 10:30–11:00 não se sobrepõem.
 * É a mesma semântica do {@code tstzrange(start, end, '[)')} usado na exclusion constraint do banco,
 * então domínio e banco concordam sobre o que é conflito.
 */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    public static TimeRange startingAt(Instant start, Duration duration) {
        return new TimeRange(start, start.plus(duration));
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    /** Sobreposição em intervalos semiabertos: um começa antes do outro terminar, nos dois sentidos. */
    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** {@code other} cabe inteiro dentro deste intervalo. */
    public boolean contains(TimeRange other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    /**
     * O início cai na grade de {@code slotMinutes} no horário LOCAL da barbearia.
     * Avaliar em UTC daria errado em fusos com meia hora (ex.: +05:30).
     */
    public boolean startsOnGrid(int slotMinutes, ZoneId zone) {
        var local = start.atZone(zone);
        return local.getSecond() == 0
                && local.getNano() == 0
                && (local.getHour() * 60 + local.getMinute()) % slotMinutes == 0;
    }
}
