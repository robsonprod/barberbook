package io.barberbook;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Falha o build se um módulo usar classes internas de outro.
 * Ex.: booking só pode usar o pacote raiz (API pública) de catalog.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BarberBookApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }
}
