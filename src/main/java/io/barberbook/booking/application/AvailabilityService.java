package io.barberbook.booking.application;

import io.barberbook.booking.domain.Booking;
import io.barberbook.booking.domain.SlotRules;
import io.barberbook.booking.infrastructure.BookingRepository;
import io.barberbook.catalog.BarberInfo;
import io.barberbook.catalog.CatalogService;
import io.barberbook.catalog.ServiceInfo;
import io.barberbook.shared.DomainException;
import io.barberbook.shared.TimeRange;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Horários livres de um serviço num dia. Consultivo: não reserva nada (a reserva pode falhar com 409
 * se alguém pegar o horário antes). Por isso READ COMMITTED basta.
 */
@Service
public class AvailabilityService {

    public record Slot(TimeRange range, List<UUID> barberIds) {
    }

    public record Availability(ServiceInfo service, LocalDate date, List<Slot> slots) {
    }

    private final CatalogService catalog;
    private final BookingRepository bookings;
    private final SlotRules rules;

    AvailabilityService(CatalogService catalog, BookingRepository bookings, SlotRules rules) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.rules = rules;
    }

    /** @param barberId opcional: null = qualquer barbeiro que faça o serviço */
    @Transactional(readOnly = true)
    public Availability query(UUID serviceId, LocalDate date, UUID barberId) {
        var service = catalog.service(serviceId);
        var now = bookings.now();
        rules.validateDate(date, now);

        List<BarberInfo> barbers;
        if (barberId != null) {
            var barber = catalog.barber(barberId);
            if (!barber.performs(serviceId)) {
                throw DomainException.validation("The barber does not perform this service");
            }
            barbers = List.of(barber);
        } else {
            barbers = catalog.barbersPerforming(serviceId);
        }

        // Uma query para as reservas ativas do dia (de todos os barbeiros envolvidos)
        var dayStart = date.atStartOfDay(rules.zone()).toInstant();
        var dayEnd = date.plusDays(1).atStartOfDay(rules.zone()).toInstant();
        Map<UUID, List<TimeRange>> busyByBarber = bookings
                .activeOverlapping(barbers.stream().map(BarberInfo::id).toList(), new TimeRange(dayStart, dayEnd))
                .stream()
                .collect(Collectors.groupingBy(Booking::barberId,
                        Collectors.mapping(Booking::slot, Collectors.toList())));

        // início -> barbeiros livres naquele início (ordenado por horário)
        Map<Instant, List<UUID>> freeByStart = new TreeMap<>();
        for (var barber : barbers) {
            var working = catalog.schedule(barber.id()).rangesOn(date, rules.zone());
            var busy = busyByBarber.getOrDefault(barber.id(), List.of());
            for (var start : rules.freeStarts(service.duration(), working, busy, now)) {
                freeByStart.computeIfAbsent(start, k -> new ArrayList<>()).add(barber.id());
            }
        }

        var slots = freeByStart.entrySet().stream()
                .map(e -> new Slot(TimeRange.startingAt(e.getKey(), service.duration()), List.copyOf(e.getValue())))
                .toList();
        return new Availability(service, date, slots);
    }
}
