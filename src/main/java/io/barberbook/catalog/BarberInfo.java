package io.barberbook.catalog;

import java.util.Set;
import java.util.UUID;

/** Barbeiro ativo e os serviços que ele faz. */
public record BarberInfo(UUID id, String name, Set<UUID> serviceIds) {

    public BarberInfo {
        serviceIds = Set.copyOf(serviceIds);
    }

    public boolean performs(UUID serviceId) {
        return serviceIds.contains(serviceId);
    }
}
