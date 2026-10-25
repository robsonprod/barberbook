-- Reservas. A regra "nunca dois agendamentos sobrepostos para o mesmo barbeiro" vive AQUI,
-- não no Java: assim ela vale com qualquer número de instâncias da aplicação e sob concorrência.

CREATE EXTENSION IF NOT EXISTS btree_gist; -- permite usar "=" sobre uuid dentro de um índice GiST

CREATE TABLE booking (
    id             uuid PRIMARY KEY,
    barber_id      uuid        NOT NULL REFERENCES barber (id),
    service_id     uuid        NOT NULL REFERENCES service (id),
    starts_at      timestamptz NOT NULL,
    ends_at        timestamptz NOT NULL,
    status         text        NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at     timestamptz,
    customer_name  text        NOT NULL,
    customer_email text        NOT NULL,
    customer_phone text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (status <> 'HELD' OR expires_at IS NOT NULL)
);

-- O coração do projeto: dois registros ATIVOS do mesmo barbeiro não podem ter faixas que se sobrepõem.
-- '[)' = início incluso, fim excluso -> 10:00–10:30 e 10:30–11:00 convivem (horários colados).
-- A violação gera SQLSTATE 23P01 (exclusion_violation), traduzido para HTTP 409 slot-unavailable.
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap
    EXCLUDE USING gist (
        barber_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('HELD', 'CONFIRMED'));

-- Disponibilidade do dia de um barbeiro
CREATE INDEX booking_barber_starts ON booking (barber_id, starts_at);
-- Sweeper: só HELD, ordenado por expiração (índice parcial, pequeno)
CREATE INDEX booking_held_expiration ON booking (expires_at) WHERE status = 'HELD';
