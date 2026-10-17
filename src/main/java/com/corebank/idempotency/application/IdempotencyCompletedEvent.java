package com.corebank.idempotency.application;

import com.corebank.idempotency.infrastructure.ReplayPointer;

import java.util.Objects;

public record IdempotencyCompletedEvent(String idempotencyKey, ReplayPointer pointer) {
    public IdempotencyCompletedEvent {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(pointer, "pointer");
    }
}
