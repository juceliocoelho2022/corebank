package com.corebank.event.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "corebank.outbox")
public record OutboxProperties(
        int batchSize,
        long fixedDelayMs,
        int maxAttempts,
        List<Duration> retryDelays) {

    public OutboxProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("corebank.outbox.batch-size must be positive");
        }
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("corebank.outbox.fixed-delay-ms must be positive");
        }
        if (maxAttempts < 2) {
            throw new IllegalArgumentException("corebank.outbox.max-attempts must be at least 2");
        }
        if (retryDelays == null || retryDelays.size() != maxAttempts - 1) {
            throw new IllegalArgumentException(
                    "corebank.outbox.retry-delays must contain exactly max-attempts - 1 values");
        }
        if (retryDelays.stream().anyMatch(delay -> delay == null || delay.isZero() || delay.isNegative())) {
            throw new IllegalArgumentException("corebank.outbox.retry-delays must contain positive durations");
        }
        retryDelays = List.copyOf(retryDelays);
    }
}
