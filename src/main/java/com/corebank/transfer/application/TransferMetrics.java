package com.corebank.transfer.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TransferMetrics {

    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void rejected(String reason) {
        registry.counter(
                "corebank.transfer.rejections",
                "outcome", "rejected",
                "reason", reason
        ).increment();
    }

    public void recordDuration(long durationNanos) {
        Timer.builder("corebank.transfer.duration")
                .description("Transfer service processing duration")
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
