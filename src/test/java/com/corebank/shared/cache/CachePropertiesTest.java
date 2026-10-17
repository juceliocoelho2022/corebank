package com.corebank.shared.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachePropertiesTest {
    @Test
    void bindsKeyPrefixAndBothTtls() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("corebank.cache.key-prefix", "corebank:v1:")
                .withProperty("corebank.cache.summary-ttl", "5m")
                .withProperty("corebank.cache.idempotency-replay-ttl", "1h");
        CacheProperties config = Binder.get(env).bind("corebank.cache", CacheProperties.class)
                .orElseThrow(() -> new IllegalStateException("Cache properties not bound"));
        assertThat(config.keyPrefix()).isEqualTo("corebank:v1:");
        assertThat(config.summaryTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.idempotencyReplayTtl()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new CacheProperties("bad", Duration.ofMinutes(5), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheProperties("corebank:v1:", Duration.ZERO, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
