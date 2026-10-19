package com.corebank.idempotency.infrastructure;

import java.util.Optional;

public interface IdempotencyReplayCache {
    Optional<ReplayPointer> find(String idempotencyKey);
    void putCompleted(String idempotencyKey, ReplayPointer pointer);
    void evict(String idempotencyKey);
}
