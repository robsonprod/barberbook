package io.barberbook.booking.domain;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Regras de agenda da barbearia: grade de horários, janela de reserva e encaixe no expediente.
 * Puro (sem banco, sem relógio global): o "agora" e o expediente chegam como parâmetros.
 */
public final class SlotRules {

    private final int slotMinutes;
    private final int windowDays;
    private final ZoneId zone;

    public SlotRules(int slotMinutes, int windowDays, ZoneId zone) {
        this.slotMinutes = slotMinutes;
        this.windowDays = windowDays;
        this.zone = zone;
    }

    public int slotMinutes() {
        return slotMinutes;
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * Valida um pedido de reserva antes de tocar no banco. A checagem de conflito com outras
     * reservas NÃO está aqui: ela é responsabilidade da exclusion constraint (à prova de corrida).
     */
    public void validateHold(TimeRange slot, boolean barberPerformsService, List<TimeRange> workingRanges, Instant now) {
        if (!barberPerformsService) {
            throw DomainException.validation("The barber does not perform this service");
        }
        if (!slot.startsOnGrid(slotMinutes, zone)) {
            throw DomainException.validation("Start time must be on the " + slotMinutes + "-minute grid");
        }
        if (!slot.start().isAfter(now)) {
            throw DomainException.validation("Start time must be in the future");
        }
        checkWindow(slot.start().atZone(zone).toLocalDate(), now);
        if (workingRanges.stream().noneMatch(r -> r.contains(slot))) {
            throw DomainException.validation("The slot is outside the barber's working hours");
        }
    }

    /**
     * Inícios livres na grade: o serviço inteiro cabe num intervalo de trabalho, não sobrepõe nenhuma
     * reserva ativa e começa depois de {@code now}. Resultado consultivo: não reserva nada.
     *
     * @param busy reservas ATIVAS do barbeiro (confirmadas ou HELD não vencidos)
     */
    public List<Instant> freeStarts(Duration serviceDuration, List<TimeRange> workingRanges,
                                    List<TimeRange> busy, Instant now) {
        var step = Duration.ofMinutes(slotMinutes);
        var starts = new ArrayList<Instant>();
        for (var working : workingRanges) {
            // o expediente começa na grade (validado no catálogo); avança de slotMinutes em slotMinutes
            for (var start = working.start(); !start.plus(serviceDuration).isAfter(working.end()); start = start.plus(step)) {
                var candidate = TimeRange.startingAt(start, serviceDuration);
                if (start.isAfter(now)
                        && candidate.startsOnGrid(slotMinutes, zone)
                        && busy.stream().noneMatch(candidate::overlaps)) {
                    starts.add(start);
                }
            }
        }
        starts.sort(null);
        return starts;
    }

    /** Data de consulta de disponibilidade: de hoje (local) até {@code windowDays} à frente. */
    public void validateDate(LocalDate date, Instant now) {
        if (date.isBefore(today(now))) {
            throw DomainException.validation("Date " + date + " is in the past");
        }
        checkWindow(date, now);
    }

    private void checkWindow(LocalDate date, Instant now) {
        if (date.isAfter(today(now).plusDays(windowDays))) {
            throw DomainException.validation("Bookings can be made at most " + windowDays + " days ahead");
        }
    }

    private LocalDate today(Instant now) {
        return now.atZone(zone).toLocalDate();
    }
}
