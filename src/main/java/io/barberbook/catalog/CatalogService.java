package io.barberbook.catalog;

import io.barberbook.catalog.internal.CatalogRepository;
import io.barberbook.shared.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * API pública do módulo catalog. É a única porta pela qual o módulo booking consulta o catálogo
 * (o teste de modularidade garante isso).
 */
@Service
public class CatalogService {

    private final CatalogRepository repository;

    CatalogService(CatalogRepository repository) {
        this.repository = repository;
    }

    public List<ServiceInfo> services() {
        return repository.activeServices();
    }

    public List<BarberInfo> barbers() {
        return repository.activeBarbers();
    }

    /** @throws DomainException not-found quando não existe ou está inativo */
    public ServiceInfo service(UUID serviceId) {
        return repository.activeService(serviceId)
                .orElseThrow(() -> DomainException.notFound("Service " + serviceId + " not found"));
    }

    /** @throws DomainException not-found quando não existe ou está inativo */
    public BarberInfo barber(UUID barberId) {
        return repository.activeBarber(barberId)
                .orElseThrow(() -> DomainException.notFound("Barber " + barberId + " not found"));
    }

    /** Barbeiros ativos que fazem o serviço. */
    public List<BarberInfo> barbersPerforming(UUID serviceId) {
        service(serviceId); // valida que o serviço existe
        return barbers().stream().filter(b -> b.performs(serviceId)).toList();
    }

    public WeeklySchedule schedule(UUID barberId) {
        return repository.schedule(barberId);
    }
}
