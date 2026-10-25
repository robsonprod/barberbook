package io.barberbook.booking.domain;

public enum BookingStatus {
    /** Horário reservado temporariamente, aguardando confirmação até {@code expiresAt}. */
    HELD,
    CONFIRMED,
    CANCELLED,
    /** HELD que não foi confirmado a tempo. */
    EXPIRED
}
