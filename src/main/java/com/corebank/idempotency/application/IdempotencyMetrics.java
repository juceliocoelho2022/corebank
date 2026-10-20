package com.corebank.idempotency.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyMetrics {

    private final MeterRegistry registry;

    public IdempotencyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void replayed() {
        increment("replayed");
    }

    public void conflict() {
        increment("conflict");
    }

    private void increment(String outcome) {
        registry.counter(
                "corebank.idempotency.operations",
                "outcome", outcome
        ).increment();
    }
}
