CREATE TABLE dead_letter_events (
    id UUID PRIMARY KEY,
    original_event_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    attempt_count INTEGER NOT NULL,
    last_error_type VARCHAR(128) NOT NULL,
    last_error_message VARCHAR(500) NULL,
    failed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_publish_error_type VARCHAR(128) NULL,
    last_publish_error_message VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NULL,

    CONSTRAINT uk_dead_letter_events_original_event UNIQUE (original_event_id),
    CONSTRAINT ck_dead_letter_events_type
        CHECK (event_type IN ('DEPOSIT_COMPLETED', 'WITHDRAWAL_COMPLETED', 'TRANSFER_COMPLETED')),
    CONSTRAINT ck_dead_letter_events_version
        CHECK (event_version >= 1),
    CONSTRAINT ck_dead_letter_events_attempt_count
        CHECK (attempt_count >= 1),
    CONSTRAINT ck_dead_letter_events_publish_attempts
        CHECK (publish_attempts >= 0),
    CONSTRAINT ck_dead_letter_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_dead_letter_events_publication_metadata
        CHECK (
            (status = 'PENDING' AND published_at IS NULL)
            OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
        )
);

CREATE INDEX idx_dead_letter_pending_next_attempt
    ON dead_letter_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';
