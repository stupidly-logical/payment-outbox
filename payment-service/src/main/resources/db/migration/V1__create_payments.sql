CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE payments (
    id               UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(255)    NOT NULL,
    merchant_id      UUID            NOT NULL,
    amount           NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    currency         CHAR(3)         NOT NULL,
    status           VARCHAR(50)     NOT NULL DEFAULT 'INITIATED',
    failure_reason   TEXT,
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_status CHECK (status IN (
        'INITIATED', 'AUTHORIZED', 'SETTLEMENT_PENDING', 'SETTLED', 'FAILED'
    ))
);

CREATE INDEX idx_payments_merchant_id ON payments (merchant_id);
CREATE INDEX idx_payments_status ON payments (status);