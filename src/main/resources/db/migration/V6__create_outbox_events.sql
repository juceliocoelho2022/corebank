CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NULL,

    CONSTRAINT ck_outbox_events_type
        CHECK (event_type IN ('DEPOSIT_COMPLETED', 'WITHDRAWAL_COMPLETED', 'TRANSFER_COMPLETED')),
    CONSTRAINT ck_outbox_events_version
        CHECK (event_version >= 1),
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_outbox_events_publication_metadata
        CHECK (
            (status = 'PENDING' AND published_at IS NULL)
            OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
        )
);

CREATE INDEX idx_outbox_pending_created_at
    ON outbox_events (created_at)
    WHERE status = 'PENDING';
