package com.corebank.idempotency.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyMetricsListenerTest {

    @Test
    void shouldCountCompletedOperation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try {
            IdempotencyMetricsListener listener =
                    new IdempotencyMetricsListener(registry);

            listener.onCompleted(null);

            double count = registry
                    .get("corebank.idempotency.operations")
                    .tag("outcome", "created")
                    .counter()
                    .count();

            assertThat(count).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }
}
