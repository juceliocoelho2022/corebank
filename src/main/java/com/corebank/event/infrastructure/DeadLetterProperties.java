package com.corebank.event.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "corebank.dlq")
public record DeadLetterProperties(
        int batchSize,
        long fixedDelayMs,
        List<Duration> retryDelays,
        Duration maxDelay) {

    public DeadLetterProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("corebank.dlq.batch-size must be positive");
        }
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("corebank.dlq.fixed-delay-ms must be positive");
        }
        if (retryDelays == null || retryDelays.isEmpty()) {
            throw new IllegalArgumentException("corebank.dlq.retry-delays must not be empty");
        }
        if (retryDelays.stream().anyMatch(delay -> delay == null || delay.isZero() || delay.isNegative())) {
            throw new IllegalArgumentException("corebank.dlq.retry-delays must contain positive durations");
        }
        if (maxDelay == null || maxDelay.isZero() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("corebank.dlq.max-delay must be positive");
        }
        retryDelays = List.copyOf(retryDelays);
    }
}
