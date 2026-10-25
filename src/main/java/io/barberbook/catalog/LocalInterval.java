package io.barberbook.catalog;

import java.time.LocalTime;
import java.util.Objects;

/** Intervalo de trabalho em hora local da barbearia (ex.: 09:00–12:00). */
public record LocalInterval(LocalTime start, LocalTime end) {

    public LocalInterval {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }
}
