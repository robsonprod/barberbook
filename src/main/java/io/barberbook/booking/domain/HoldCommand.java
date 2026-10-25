package io.barberbook.booking.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Pedido de reserva já com dados normalizados. */
public record HoldCommand(UUID barberId, UUID serviceId, Instant start, Customer customer) {

    /**
     * Hash canônico para idempotência: calculado sobre os campos normalizados, não sobre o JSON bruto.
     * Ordem dos campos, espaços ou formato do telefone no JSON não mudam o hash; mudar o conteúdo muda.
     */
    public String canonicalHash() {
        var canonical = String.join("\u001F", // separador que não aparece nos dados
                barberId.toString(), serviceId.toString(), start.toString(),
                customer.name(), customer.email().toLowerCase(), customer.phone());
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
