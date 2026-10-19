package com.corebank.shared.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCacheErrorHandlerTest {
    @Test
    void cacheFailuresNeverEscape() {
        RedisCacheErrorHandler handler = new RedisCacheErrorHandler();
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("customer-summary");
        RuntimeException failure = new IllegalStateException("do not log redis contents");
        assertThatCode(() -> handler.handleCacheGetError(failure, cache, "key")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCachePutError(failure, cache, "key", "value")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheEvictError(failure, cache, "key")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheClearError(failure, cache)).doesNotThrowAnyException();
    }
}
