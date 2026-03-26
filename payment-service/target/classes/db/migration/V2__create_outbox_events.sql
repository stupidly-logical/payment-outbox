CREATE TABLE outbox_events (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    UUID         NOT NULL REFERENCES payments(id),
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count   INT          NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_outbox_status CHECK (status IN (
        'PENDING', 'PUBLISHED', 'FAILED'
    ))
);

-- Partial index: poller only ever queries PENDING rows
CREATE INDEX idx_outbox_pending
    ON outbox_events (created_at ASC)
    WHERE status = 'PENDING';