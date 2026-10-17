package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.shared.cache.CacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisIdempotencyReplayCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CacheProperties config = new CacheProperties("corebank:v1:", Duration.ofMinutes(5), Duration.ofHours(1));
    private RedisIdempotencyReplayCache cache;

    @BeforeEach
    void setUp() {
        cache = new RedisIdempotencyReplayCache(redis, mapper, config);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void cacheMissReturnsEmpty() {
        assertThat(cache.find("some-key-123")).isEmpty();
    }

    @Test
    void serializesPointerWithoutFinancialResponseOrRawKey() {
        var pointer = new ReplayPointer(UUID.randomUUID(), IdempotencyOperation.DEPOSIT,
                "a".repeat(64), UUID.randomUUID(), 201);
        cache.putCompleted("raw-sensitive-key-123", pointer);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(IdempotencyCacheKey.redisKey("corebank:v1:", "raw-sensitive-key-123")),
                json.capture(), eq(Duration.ofHours(1)));
        assertThat(json.getValue()).contains("recordId", "requestHash", "httpStatus");
        assertThat(json.getValue()).doesNotContain("raw-sensitive-key-123", "balance",
                "responseBody", "description", "email", "document");
    }

    @Test
    void deserializesValidPointer() throws Exception {
        String key = "some-key-123";
        var pointer = new ReplayPointer(UUID.randomUUID(), IdempotencyOperation.TRANSFER, "b".repeat(64), null, 201);
        when(values.get(IdempotencyCacheKey.redisKey("corebank:v1:", key)))
                .thenReturn(mapper.writeValueAsString(pointer));
        assertThat(cache.find(key)).contains(pointer);
    }

    @Test
    void malformedJsonIsEvictedAndTreatedAsMiss() {
        String key = "some-key-123";
        String redisKey = IdempotencyCacheKey.redisKey("corebank:v1:", key);
        when(values.get(redisKey)).thenReturn("corrupted-json");
        assertThat(cache.find(key)).isEmpty();
        verify(redis).delete(redisKey);
    }

    @Test
    void redisOutageIsFailOpenOnGetPutAndEvict() {
        String key = "some-key-123";
        String redisKey = IdempotencyCacheKey.redisKey("corebank:v1:", key);
        when(values.get(redisKey)).thenThrow(new RedisConnectionFailureException("unavailable"));
        assertThatCode(() -> cache.find(key)).doesNotThrowAnyException();
        doThrow(new RedisConnectionFailureException("unavailable")).when(redis).delete(redisKey);
        assertThatCode(() -> cache.evict(key)).doesNotThrowAnyException();
        doThrow(new RedisConnectionFailureException("unavailable")).when(values)
                .set(eq(redisKey), anyString(), eq(Duration.ofHours(1)));
        var pointer = new ReplayPointer(UUID.randomUUID(), IdempotencyOperation.DEPOSIT,
                "a".repeat(64), null, 201);
        assertThatCode(() -> cache.putCompleted(key, pointer)).doesNotThrowAnyException();
    }
}
