package io.barberbook.shared;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ShopProperties.class)
class SharedConfiguration {

    /** Relógio injetável: em testes de domínio usamos Clock.fixed. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId shopZoneId(ShopProperties properties) {
        return properties.zoneId();
    }
}
