package io.barberbook.booking.web;

import io.barberbook.booking.application.BookingService;
import io.barberbook.booking.domain.Booking;
import io.barberbook.booking.domain.Customer;
import io.barberbook.booking.domain.HoldCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@Validated
@Tag(name = "Bookings", description = "Hold a slot, then confirm or cancel it")
class BookingController {

    private final BookingService bookings;
    private final ZoneId zone;

    BookingController(BookingService bookings, ZoneId shopZoneId) {
        this.bookings = bookings;
        this.zone = shopZoneId;
    }

    record CustomerRequest(@NotBlank String name, @NotBlank String email, @NotBlank String phone) {
    }

    record HoldRequest(@NotNull UUID barberId, @NotNull UUID serviceId, @NotNull OffsetDateTime startAt,
                       @NotNull @Valid CustomerRequest customer) {
    }

    record CustomerResponse(String name, String email, String phone) {
    }

    record BookingResponse(UUID id, String status, UUID barberId, UUID serviceId,
                           OffsetDateTime startAt, OffsetDateTime endAt, OffsetDateTime expiresAt,
                           CustomerResponse customer) {
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Held: the slot is reserved for 10 minutes")
    @ApiResponse(responseCode = "200", description = "Replay of a previous request with the same Idempotency-Key")
    @ApiErrors.ValidationAndNotFound
    @ApiErrors.Conflict
    @ApiErrors.IdempotencyConflict
    @Operation(summary = "Hold a slot for 10 minutes",
            description = "Idempotent: repeating the request with the same Idempotency-Key returns the original "
                    + "booking (200, header Idempotent-Replayed: true). Errors: 400 validation, 404 not-found, "
                    + "409 slot-unavailable, 422 idempotency-conflict.")
    ResponseEntity<BookingResponse> hold(
            @Parameter(description = "Unique key per booking attempt; reuse it only to retry the same request")
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {
        var command = new HoldCommand(request.barberId(), request.serviceId(), request.startAt().toInstant(),
                new Customer(request.customer().name(), request.customer().email(), request.customer().phone()));
        var result = bookings.hold(idempotencyKey, command);
        var body = toResponse(result.booking(), bookings.now());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    @ApiErrors.ValidationAndNotFound
    @Operation(summary = "Read a booking")
    BookingResponse get(@PathVariable UUID id) {
        return toResponse(bookings.get(id), bookings.now());
    }

    @PostMapping("/{id}/confirm")
    @ApiErrors.ValidationAndNotFound
    @ApiErrors.Conflict
    @Operation(summary = "Confirm a held booking",
            description = "Idempotent. 409 booking-expired after 10 minutes, 409 booking-state-conflict if cancelled.")
    BookingResponse confirm(@PathVariable UUID id) {
        return toResponse(bookings.confirm(id), bookings.now());
    }

    @PostMapping("/{id}/cancel")
    @ApiErrors.ValidationAndNotFound
    @ApiErrors.Conflict
    @Operation(summary = "Cancel a held or confirmed booking", description = "Idempotent. Frees the slot immediately.")
    BookingResponse cancel(@PathVariable UUID id) {
        return toResponse(bookings.cancel(id), bookings.now());
    }

    private BookingResponse toResponse(Booking b, Instant now) {
        var c = b.customer();
        return new BookingResponse(b.id(), b.effectiveStatus(now).name(), b.barberId(), b.serviceId(),
                local(b.slot().start()), local(b.slot().end()),
                b.isActiveAt(now) && b.expiresAt() != null ? local(b.expiresAt()) : null,
                new CustomerResponse(c.name(), c.email(), c.phone()));
    }

    /** Instantes saem no offset da barbearia: 2026-11-10T09:00:00-03:00. */
    private OffsetDateTime local(Instant instant) {
        return instant.atZone(zone).toOffsetDateTime();
    }
}
