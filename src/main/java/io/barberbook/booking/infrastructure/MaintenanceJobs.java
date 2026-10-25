package io.barberbook.booking.infrastructure;

import io.barberbook.shared.ShopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Jobs periódicos. Ambos são UPDATE/DELETE idempotentes: rodar em várias instâncias ao mesmo tempo
 * só custa uma query a mais, então não precisam de lock distribuído (ShedLock etc.).
 */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final BookingRepository bookings;
    private final IdempotencyRepository idempotency;
    private final ShopProperties shop;

    MaintenanceJobs(BookingRepository bookings, IdempotencyRepository idempotency, ShopProperties shop) {
        this.bookings = bookings;
        this.idempotency = idempotency;
        this.shop = shop;
    }

    /** Marca como EXPIRED os HELD vencidos (a liberação do horário já aconteceu no expires_at). */
    @Scheduled(fixedDelayString = "${barberbook.sweeper-interval}", initialDelayString = "${barberbook.sweeper-interval}")
    public int expireOverdueHolds() {
        var expired = bookings.expireOverdueHolds();
        if (expired > 0) {
            log.info("Expired {} overdue holds", expired);
        }
        return expired;
    }

    @Scheduled(fixedDelayString = "1h", initialDelayString = "1h")
    public int purgeIdempotencyKeys() {
        return idempotency.purgeOlderThan(shop.idempotencyRetention());
    }
}
