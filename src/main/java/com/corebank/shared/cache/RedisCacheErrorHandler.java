package com.corebank.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

@Component
public class RedisCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        warn("GET", exception, cache);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        warn("PUT", exception, cache);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        warn("EVICT", exception, cache);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        warn("CLEAR", exception, cache);
    }

    private void warn(String operation, RuntimeException failure, Cache cache) {
        log.warn("Optional cache degraded operation={} cache={} errorType={}",
                operation, cache.getName(), failure.getClass().getSimpleName());
    }
}
