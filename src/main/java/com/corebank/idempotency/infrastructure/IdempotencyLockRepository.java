package com.corebank.idempotency.infrastructure;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyLockRepository {

    private final EntityManager entityManager;

    public IdempotencyLockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public boolean tryLock(String key) {
        Object result = entityManager.createNativeQuery(
                        "select pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", key)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
