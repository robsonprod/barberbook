package io.barberbook.shared;

/** Tipos de erro estáveis da API (RFC 9457). Clientes podem depender do {@code code}. */
public enum ProblemType {
    VALIDATION(400, "validation", "Invalid request"),
    NOT_FOUND(404, "not-found", "Resource not found"),
    SLOT_UNAVAILABLE(409, "slot-unavailable", "Slot unavailable"),
    BOOKING_EXPIRED(409, "booking-expired", "Booking expired"),
    BOOKING_STATE_CONFLICT(409, "booking-state-conflict", "Booking state conflict"),
    IDEMPOTENCY_CONFLICT(422, "idempotency-conflict", "Idempotency key reused");

    private final int status;
    private final String code;
    private final String title;

    ProblemType(int status, String code, String title) {
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
