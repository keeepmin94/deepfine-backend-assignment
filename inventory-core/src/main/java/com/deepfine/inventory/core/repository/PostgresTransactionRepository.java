package com.deepfine.inventory.core.repository;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresTransactionRepository {

    private final EntityManager entityManager;
    private final String lockTimeout;

    public PostgresTransactionRepository(EntityManager entityManager,
            @Value("${inventory.lock-timeout-ms:3000}") int lockTimeoutMillis) {
        if (lockTimeoutMillis <= 0) {
            throw new IllegalArgumentException("inventory.lock-timeout-ms must be positive");
        }
        this.entityManager = entityManager;
        this.lockTimeout = lockTimeoutMillis + "ms";
    }

    public void applyLockTimeout() {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeout)
                .getSingleResult();
    }
}
