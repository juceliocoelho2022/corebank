ALTER TABLE outbox_events
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NULL,
    ADD COLUMN last_error_type VARCHAR(128) NULL,
    ADD COLUMN last_error_message VARCHAR(500) NULL;

UPDATE outbox_events
SET next_attempt_at = created_at
WHERE next_attempt_at IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN next_attempt_at SET NOT NULL;

ALTER TABLE outbox_events
    DROP CONSTRAINT ck_outbox_events_status,
    DROP CONSTRAINT ck_outbox_events_publication_metadata;

ALTER TABLE outbox_events
    ADD CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD_LETTERED')),
    ADD CONSTRAINT ck_outbox_events_attempt_count
        CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_outbox_events_publication_metadata
        CHECK (
            (status IN ('PENDING', 'DEAD_LETTERED') AND published_at IS NULL)
            OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
        );

DROP INDEX IF EXISTS idx_outbox_pending_created_at;

CREATE INDEX idx_outbox_pending_next_attempt
    ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';
