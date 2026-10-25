package io.barberbook.shared;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Regras da barbearia que não mudam por requisição.
 *
 * @param zoneId             fuso do negócio; datas locais são convertidas por ele (nunca "-03:00" fixo)
 * @param slotMinutes        granularidade da grade de horários
 * @param bookingWindowDays  até quantos dias à frente se pode reservar
 * @param holdDuration       quanto tempo um HELD segura o horário
 * @param sweeperInterval    frequência do job que marca HELD vencidos como EXPIRED
 * @param idempotencyRetention por quanto tempo guardar chaves de idempotência
 */
@ConfigurationProperties("barberbook")
public record ShopProperties(
        ZoneId zoneId,
        int slotMinutes,
        int bookingWindowDays,
        Duration holdDuration,
        Duration sweeperInterval,
        Duration idempotencyRetention) {
}
