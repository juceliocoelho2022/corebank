package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.application.IdempotencyCompletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IdempotencyMetricsListener {

    private final MeterRegistry registry;

    public IdempotencyMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(IdempotencyCompletedEvent event) {
        registry.counter(
                "corebank.idempotency.operations",
                "outcome", "created"
        ).increment();
    }
}
