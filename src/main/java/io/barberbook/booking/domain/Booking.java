package io.barberbook.booking.domain;

import static io.barberbook.booking.domain.BookingStatus.CANCELLED;
import static io.barberbook.booking.domain.BookingStatus.CONFIRMED;
import static io.barberbook.booking.domain.BookingStatus.EXPIRED;
import static io.barberbook.booking.domain.BookingStatus.HELD;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import io.barberbook.shared.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado Booking: decide quais transições de estado são válidas.
 *
 * <p>É imutável: cada transição devolve um novo Booking. O banco torna a transição atômica
 * (UPDATE condicional); aqui fica a regra, testável sem banco e com o tempo injetado ({@code now}).
 *
 * <pre>
 * HELD ──confirm (antes de expiresAt)──▶ CONFIRMED ──cancel──▶ CANCELLED
 *   │ └──cancel──────────────────────────────────────────────▶ CANCELLED
 *   └──expiresAt atingido──▶ EXPIRED
 * </pre>
 */
public record Booking(
        UUID id,
        UUID barberId,
        UUID serviceId,
        TimeRange slot,
        Customer customer,
        BookingStatus status,
        Instant expiresAt) {

    public Booking {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(barberId, "barberId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(status, "status");
        if (status == HELD && expiresAt == null) {
            throw new IllegalArgumentException("a HELD booking needs an expiration");
        }
    }

    public static Booking hold(UUID id, UUID barberId, UUID serviceId, TimeRange slot, Customer customer,
                               Instant now, Duration holdDuration) {
        return new Booking(id, barberId, serviceId, slot, customer, HELD, now.plus(holdDuration));
    }

    /** Ocupa o horário? Confirmado sempre; HELD só antes de expirar ({@code expiresAt} é exclusivo). */
    public boolean isActiveAt(Instant now) {
        return status == CONFIRMED || (status == HELD && now.isBefore(expiresAt));
    }

    /** Status visto pelo cliente: um HELD vencido já aparece como EXPIRED, mesmo antes do sweeper. */
    public BookingStatus effectiveStatus(Instant now) {
        return isExpiredHold(now) ? EXPIRED : status;
    }

    public Booking confirm(Instant now) {
        return switch (status) {
            case CONFIRMED -> this; // idempotente
            case HELD -> {
                if (isExpiredHold(now)) {
                    throw expired();
                }
                yield with(CONFIRMED, null);
            }
            case EXPIRED -> throw expired();
            case CANCELLED -> throw new DomainException(ProblemType.BOOKING_STATE_CONFLICT,
                    "Booking " + id + " is cancelled and cannot be confirmed");
        };
    }

    public Booking cancel(Instant now) {
        return switch (status) {
            case CANCELLED -> this; // idempotente
            case CONFIRMED -> with(CANCELLED, null);
            case HELD -> {
                if (isExpiredHold(now)) {
                    throw expired();
                }
                yield with(CANCELLED, null);
            }
            case EXPIRED -> throw expired();
        };
    }

    /** Marca como EXPIRED se for um HELD vencido; caso contrário não muda nada. */
    public Booking expire(Instant now) {
        return isExpiredHold(now) ? with(EXPIRED, expiresAt) : this;
    }

    private boolean isExpiredHold(Instant now) {
        return status == HELD && !now.isBefore(expiresAt);
    }

    private DomainException expired() {
        return new DomainException(ProblemType.BOOKING_EXPIRED, "Booking " + id + " has expired");
    }

    private Booking with(BookingStatus newStatus, Instant newExpiresAt) {
        return new Booking(id, barberId, serviceId, slot, customer, newStatus, newExpiresAt);
    }

    @Override
    public String toString() {
        // sem dados do cliente
        return "Booking[id=" + id + ", barberId=" + barberId + ", slot=" + slot + ", status=" + status + "]";
    }
}
