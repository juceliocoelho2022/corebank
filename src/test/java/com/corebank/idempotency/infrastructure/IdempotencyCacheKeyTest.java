package com.corebank.idempotency.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyCacheKeyTest {
    @Test
    void hashesRawKeyAsLowercaseUtf8Sha256() {
        assertThat(IdempotencyCacheKey.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void neverLeaksRawIdempotencyKeyIntoRedisKey() {
        String raw = "payment-sensitive-key-123";
        String key = IdempotencyCacheKey.redisKey("corebank:v1:", raw);
        assertThat(key).startsWith("corebank:v1:idempotency:");
        assertThat(key).doesNotContain(raw);
        assertThat(key.substring("corebank:v1:idempotency:".length())).matches("[0-9a-f]{64}");
    }
}
