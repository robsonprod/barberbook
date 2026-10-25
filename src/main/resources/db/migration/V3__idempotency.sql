-- Chaves de idempotência do POST /bookings.
-- Gravada na MESMA transação da reserva: ou as duas existem, ou nenhuma.
CREATE TABLE idempotency_key (
    key          text PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 255),
    request_hash text        NOT NULL,
    -- A chave é inserida ANTES da reserva (para serializar requisições duplicadas logo no início),
    -- então a FK só é conferida no commit.
    booking_id   uuid        NOT NULL REFERENCES booking (id) DEFERRABLE INITIALLY DEFERRED,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idempotency_key_created ON idempotency_key (created_at);
