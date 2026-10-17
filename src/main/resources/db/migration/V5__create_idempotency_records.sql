CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resource_id UUID NULL,
    response_body JSONB NULL,
    http_status INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,

    CONSTRAINT uk_idempotency_records_key UNIQUE (idempotency_key),
    CONSTRAINT ck_idempotency_records_key_length
        CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT ck_idempotency_records_operation_type
        CHECK (operation_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_idempotency_records_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_records_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_records_completion_metadata
        CHECK (
            status <> 'COMPLETED'
            OR (
                response_body IS NOT NULL
                AND http_status IS NOT NULL
                AND completed_at IS NOT NULL
            )
        )
);
