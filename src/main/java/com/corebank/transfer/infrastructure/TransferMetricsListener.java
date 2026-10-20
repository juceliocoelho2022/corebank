package com.corebank.transfer.infrastructure;

import com.corebank.transfer.application.TransferCommittedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransferMetricsListener {

    private final MeterRegistry registry;

    public TransferMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCommitted(TransferCommittedEvent event) {
        registry.counter(
                "corebank.transfer.operations",
                "outcome", "completed"
        ).increment();
    }
}
