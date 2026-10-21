package com.corebank.transfer.application;

import com.corebank.shared.exception.ResourceNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferMetricsExecutorTest {

    @Test
    void shouldMeasureSuccessfulExecution() {
        var registry = new SimpleMeterRegistry();
        try {
            var executor = new TransferMetricsExecutor(
                    new TransferMetrics(registry));

            String result = executor.execute(() -> "ok");

            assertThat(result).isEqualTo("ok");
            assertThat(registry.get("corebank.transfer.duration")
                    .timer().count()).isEqualTo(1);
            assertThat(registry.find("corebank.transfer.rejections")
                    .counter()).isNull();
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldCountRejectionAndPreserveException() {
        var registry = new SimpleMeterRegistry();
        try {
            var executor = new TransferMetricsExecutor(
                    new TransferMetrics(registry));

            var original = new ResourceNotFoundException(
                    "Source account not found");

            assertThatThrownBy(() -> executor.execute(() -> {
                throw original;
            })).isSameAs(original);

            assertThat(registry.get("corebank.transfer.rejections")

                    .tag("reason", "account_not_found")
                    .counter().count()).isEqualTo(1.0);

            assertThat(registry.get("corebank.transfer.duration")
                    .timer().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
