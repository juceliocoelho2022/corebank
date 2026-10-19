package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.domain.IdempotencyRecord;

import java.util.Objects;
import java.util.UUID;

public record ReplayPointer(
        UUID recordId, IdempotencyOperation operation, String requestHash,
        UUID resourceId, int httpStatus) {

    public ReplayPointer {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(operation, "operation");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 digest");
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("invalid HTTP status");
        }
    }

    public static ReplayPointer from(IdempotencyRecord record) {
        return new ReplayPointer(record.getId(), record.getOperationType(), record.getRequestHash(),
                record.getResourceId(), record.getHttpStatus());
    }
}
