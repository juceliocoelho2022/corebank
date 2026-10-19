package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.application.IdempotencyCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IdempotencyReplayCacheWriter {

    private final IdempotencyReplayCache replayCache;

    public IdempotencyReplayCacheWriter(IdempotencyReplayCache replayCache) {
        this.replayCache = replayCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(IdempotencyCompletedEvent event) {
        replayCache.putCompleted(event.idempotencyKey(), event.pointer());
    }
}
