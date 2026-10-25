package io.barberbook.booking.web;

import io.barberbook.booking.application.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/availability")
@Tag(name = "Availability", description = "Free start times — advisory, nothing is reserved")
class AvailabilityController {

    private final AvailabilityService availability;
    private final ZoneId zone;

    AvailabilityController(AvailabilityService availability, ZoneId shopZoneId) {
        this.availability = availability;
        this.zone = shopZoneId;
    }

    record SlotResponse(OffsetDateTime start, OffsetDateTime end, List<UUID> barberIds) {
    }

    record AvailabilityResponse(UUID serviceId, String serviceName, long durationMinutes, BigDecimal price,
                                LocalDate date, List<SlotResponse> slots) {
    }

    @GetMapping
    @ApiErrors.ValidationAndNotFound
    @Operation(summary = "Free start times for a service on a local date",
            description = "Omit barberId to search all barbers who perform the service. "
                    + "Errors: 400 validation (date outside the 30-day window, barber does not perform the service), "
                    + "404 not-found.")
    AvailabilityResponse query(
            @RequestParam UUID serviceId,
            @Parameter(description = "Shop-local date, ISO-8601 (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID barberId) {
        var result = availability.query(serviceId, date, barberId);
        var s = result.service();
        return new AvailabilityResponse(s.id(), s.name(), s.duration().toMinutes(), s.price(), result.date(),
                result.slots().stream()
                        .map(slot -> new SlotResponse(
                                slot.range().start().atZone(zone).toOffsetDateTime(),
                                slot.range().end().atZone(zone).toOffsetDateTime(),
                                slot.barberIds()))
                        .toList());
    }
}
