-- Consumer-side idempotency table
-- Each consumer group tracks which event IDs it has already processed
CREATE TABLE processed_events (
    event_id         UUID         NOT NULL,
    consumer_group   VARCHAR(100) NOT NULL,
    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer_group)
);