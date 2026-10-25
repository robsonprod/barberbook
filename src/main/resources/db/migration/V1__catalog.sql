-- Catálogo da barbearia: barbeiros, serviços e horário semanal de trabalho.

CREATE TABLE barber (
    id     uuid PRIMARY KEY,
    name   text    NOT NULL,
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE service (
    id               uuid PRIMARY KEY,
    name             text    NOT NULL,
    -- Duração sempre múltipla da grade de 15 min: a regra vale também para quem escrever direto no banco.
    duration_minutes integer NOT NULL CHECK (duration_minutes > 0 AND duration_minutes % 15 = 0),
    price_cents      integer NOT NULL CHECK (price_cents >= 0),
    currency         char(3) NOT NULL DEFAULT 'BRL',
    active           boolean NOT NULL DEFAULT true
);

CREATE TABLE barber_service (
    barber_id  uuid NOT NULL REFERENCES barber (id),
    service_id uuid NOT NULL REFERENCES service (id),
    PRIMARY KEY (barber_id, service_id)
);

-- Horário no fuso da barbearia (hora local), por dia da semana ISO (1 = segunda ... 7 = domingo).
CREATE TABLE working_interval (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    barber_id   uuid     NOT NULL REFERENCES barber (id),
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    starts_at   time     NOT NULL,
    ends_at     time     NOT NULL,
    CHECK (ends_at > starts_at)
);

CREATE INDEX working_interval_barber_day ON working_interval (barber_id, day_of_week);
