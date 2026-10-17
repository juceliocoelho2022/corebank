package com.corebank.idempotency.infrastructure;

import com.corebank.shared.cache.CacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RedisIdempotencyReplayCache implements IdempotencyReplayCache {
    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyReplayCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheProperties properties;

    public RedisIdempotencyReplayCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public Optional<ReplayPointer> find(String idempotencyKey) {
        String key = IdempotencyCacheKey.redisKey(properties.keyPrefix(), idempotencyKey);
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (DataAccessException ex) {
            degraded("GET", ex);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, ReplayPointer.class));
        } catch (JsonProcessingException | RuntimeException ex) {
            degraded("DESERIALIZE", ex);
            evict(idempotencyKey);
            return Optional.empty();
        }
    }

    @Override
    public void putCompleted(String idempotencyKey, ReplayPointer pointer) {
        try {
            String json = mapper.writeValueAsString(pointer);
            redis.opsForValue().set(IdempotencyCacheKey.redisKey(properties.keyPrefix(), idempotencyKey),
                    json, properties.idempotencyReplayTtl());
        } catch (JsonProcessingException | DataAccessException ex) {
            degraded("PUT", ex);
        }
    }

    @Override
    public void evict(String idempotencyKey) {
        try {
            redis.delete(IdempotencyCacheKey.redisKey(properties.keyPrefix(), idempotencyKey));
        } catch (DataAccessException ex) {
            degraded("EVICT", ex);
        }
    }

    private void degraded(String operation, Exception ex) {
        log.warn("Optional idempotency replay cache degraded operation={} errorType={}",
                operation, ex.getClass().getSimpleName());
    }
}
