package io.barberbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BarberBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(BarberBookApplication.class, args);
    }
}
