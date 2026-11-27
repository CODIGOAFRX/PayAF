-- Esquema inicial de PayAF (perfil postgres). Lo aplica Flyway; Hibernate solo lo valida.

-- Pagos: todo lo que necesita Payment.rehydrate más la versión optimista.
CREATE TABLE payments (
    id                 uuid         PRIMARY KEY,
    amount_minor       bigint       NOT NULL CHECK (amount_minor > 0),
    currency           varchar(3)   NOT NULL,
    country            varchar(2)   NOT NULL,
    merchant_reference varchar(255) NOT NULL,
    idempotency_key    varchar(255) NOT NULL,
    status             varchar(32)  NOT NULL,
    authorized_by      varchar(64),
    gateway_reference  varchar(255),
    failure_reason     varchar(1000),
    refunded_minor     bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    -- Versión de @Version: empieza en 0 al insertar (Payment.version() es esta más uno)
    version            bigint       NOT NULL
);

-- Las notificaciones de las PSP buscan el pago por su referencia en la pasarela
CREATE INDEX payments_gateway_reference_idx ON payments (authorized_by, gateway_reference);
-- El reaper busca PENDING antiguos
CREATE INDEX payments_status_updated_idx ON payments (status, updated_at);
-- Los últimos pagos, para el panel
CREATE INDEX payments_created_idx ON payments (created_at DESC);

-- Intentos de autorización de cada pago, en orden
CREATE TABLE payment_attempts (
    id                bigserial    PRIMARY KEY,
    payment_id        uuid         NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    seq               integer      NOT NULL,
    gateway           varchar(64)  NOT NULL,
    outcome           varchar(16)  NOT NULL,
    latency_nanos     bigint       NOT NULL,
    code              varchar(255),
    gateway_reference varchar(255),
    attempted_at      timestamptz  NOT NULL,
    UNIQUE (payment_id, seq)
);

-- Reembolsos de cada pago; la referencia de la PSP no se repite dentro de un pago
CREATE TABLE payment_refunds (
    id                       bigserial    PRIMARY KEY,
    payment_id               uuid         NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    gateway_refund_reference varchar(255) NOT NULL,
    amount_minor             bigint       NOT NULL CHECK (amount_minor > 0),
    refunded_at              timestamptz  NOT NULL,
    CONSTRAINT payment_refunds_reference_uq UNIQUE (payment_id, gateway_refund_reference)
);

-- Claves de idempotencia: la clave primaria hace atómico el «reservar si no existe»
CREATE TABLE idempotency_keys (
    key          varchar(255) PRIMARY KEY,
    fingerprint  char(64)     NOT NULL,
    payment_id   uuid         NOT NULL,
    reserved_at  timestamptz  NOT NULL,
    completed_at timestamptz
);

-- Estado de cada circuit breaker, serializado en JSON
CREATE TABLE circuit_breakers (
    gateway    varchar(64) PRIMARY KEY,
    state      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL
);

-- Notificaciones de las PSP ya procesadas (para descartar reintentos)
CREATE TABLE processed_notifications (
    gateway         varchar(64)  NOT NULL,
    notification_id varchar(255) NOT NULL,
    processed_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (gateway, notification_id)
);

-- Outbox: los eventos se escriben en la misma transacción que el cambio que los produce
CREATE TABLE outbox (
    id             bigserial    PRIMARY KEY,
    aggregate_type varchar(32)  NOT NULL,
    aggregate_id   varchar(64)  NOT NULL,
    event_type     varchar(64)  NOT NULL,
    payload        jsonb        NOT NULL,
    occurred_at    timestamptz  NOT NULL,
    published_at   timestamptz
);

-- El relay solo mira los pendientes
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
