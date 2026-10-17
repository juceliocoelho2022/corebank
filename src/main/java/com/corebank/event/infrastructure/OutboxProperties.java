package com.corebank.event.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "corebank.outbox")
public record OutboxProperties(int batchSize, long fixedDelayMs) {

    public OutboxProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("corebank.outbox.batch-size must be positive");
        }
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("corebank.outbox.fixed-delay-ms must be positive");
        }
    }
}
