package io.barberbook.booking.application;

import io.barberbook.booking.domain.SlotRules;
import io.barberbook.shared.ShopProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class BookingConfiguration {

    @Bean
    SlotRules slotRules(ShopProperties shop) {
        return new SlotRules(shop.slotMinutes(), shop.bookingWindowDays(), shop.zoneId());
    }
}
