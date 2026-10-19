package com.corebank.shared.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "corebank.cache")
public record CacheProperties(String keyPrefix, Duration summaryTtl, Duration idempotencyReplayTtl) {

    public CacheProperties {
        if (keyPrefix == null || keyPrefix.isBlank() || !keyPrefix.endsWith(":")) {
            throw new IllegalArgumentException("corebank.cache.key-prefix must end with ':'");
        }
        if (summaryTtl == null || summaryTtl.isZero() || summaryTtl.isNegative()) {
            throw new IllegalArgumentException("corebank.cache.summary-ttl must be positive");
        }
        if (idempotencyReplayTtl == null || idempotencyReplayTtl.isZero() || idempotencyReplayTtl.isNegative()) {
            throw new IllegalArgumentException("corebank.cache.idempotency-replay-ttl must be positive");
        }
    }
}
