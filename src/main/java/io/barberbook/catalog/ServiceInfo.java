package io.barberbook.catalog;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/** Serviço oferecido pela barbearia. */
public record ServiceInfo(UUID id, String name, Duration duration, BigDecimal price, String currency) {
}
