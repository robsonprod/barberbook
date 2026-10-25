package io.barberbook.catalog.web;

import io.barberbook.catalog.BarberInfo;
import io.barberbook.catalog.CatalogService;
import io.barberbook.catalog.ServiceInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    record ServiceResponse(UUID id, String name, long durationMinutes, BigDecimal price, String currency) {
        static ServiceResponse of(ServiceInfo s) {
            return new ServiceResponse(s.id(), s.name(), s.duration().toMinutes(), s.price(), s.currency());
        }
    }

    record BarberResponse(UUID id, String name, List<UUID> serviceIds) {
        static BarberResponse of(BarberInfo b) {
            return new BarberResponse(b.id(), b.name(), b.serviceIds().stream().sorted().toList());
        }
    }

    @GetMapping("/services")
    List<ServiceResponse> services() {
        return catalog.services().stream().map(ServiceResponse::of).toList();
    }

    @GetMapping("/barbers")
    List<BarberResponse> barbers() {
        return catalog.barbers().stream().map(BarberResponse::of).toList();
    }
}
