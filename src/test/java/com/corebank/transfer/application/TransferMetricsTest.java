package com.corebank.transfer.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransferMetricsTest {

    @Test
    void shouldCountRejectedTransfer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            TransferMetrics metrics = new TransferMetrics(registry);

            metrics.rejected("insufficient_balance");

            double count = registry.get("corebank.transfer.rejections")
                    
                    .tag("reason", "insufficient_balance")
                    .counter()
                    .count();

            assertThat(count).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldRecordTransferDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            TransferMetrics metrics = new TransferMetrics(registry);

            metrics.recordDuration(TimeUnit.MILLISECONDS.toNanos(25));

            var timer = registry.get("corebank.transfer.duration").timer();

            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(25.0);
        } finally {
            registry.close();
        }
    }
}
