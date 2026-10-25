package io.barberbook.booking.infrastructure;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record StoredKey(String requestHash, UUID bookingId) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tenta reservar a chave. {@code ON CONFLICT DO NOTHING} em vez de capturar o erro de unique:
     * no PostgreSQL um erro aborta a transação inteira. Se outra transação estiver inserindo a mesma
     * chave agora, o banco faz ESTA esperar a outra terminar — é isso que serializa os duplicados.
     *
     * @return true se a chave é nova (esta requisição é a original)
     */
    public boolean claim(String key, String requestHash, UUID bookingId) {
        return jdbc.sql("""
                        INSERT INTO idempotency_key (key, request_hash, booking_id)
                        VALUES (:key, :hash, :booking)
                        ON CONFLICT (key) DO NOTHING""")
                .param("key", key)
                .param("hash", requestHash)
                .param("booking", bookingId)
                .update() == 1;
    }

    public Optional<StoredKey> find(String key) {
        return jdbc.sql("SELECT request_hash, booking_id FROM idempotency_key WHERE key = :key")
                .param("key", key)
                .query((rs, n) -> new StoredKey(rs.getString("request_hash"), rs.getObject("booking_id", UUID.class)))
                .optional();
    }

    public int purgeOlderThan(Duration retention) {
        return jdbc.sql("DELETE FROM idempotency_key WHERE created_at < now() - make_interval(secs => :secs)")
                .param("secs", retention.toSeconds())
                .update();
    }
}
