package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.application.IdempotencyCompletedEvent;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.infrastructure.ReplayPointer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyMetricsTransactionTest {

    @Test
    void shouldCountOnlyAfterCommit() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            ApplicationEventPublisher publisher = context;
            var registry = context.getBean(SimpleMeterRegistry.class);
            var template = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));

            template.executeWithoutResult(status -> {
                publisher.publishEvent(event());
                assertThat(counter(registry)).isZero();
            });

            assertThat(counter(registry)).isEqualTo(1.0);
        }
    }

    @Test
    void shouldNotCountAfterRollback() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            ApplicationEventPublisher publisher = context;
            var registry = context.getBean(SimpleMeterRegistry.class);
            var template = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));

            template.executeWithoutResult(status -> {
                publisher.publishEvent(event());
                assertThat(counter(registry)).isZero();
                status.setRollbackOnly();
            });

            assertThat(counter(registry)).isZero();
        }
    }

    private static IdempotencyCompletedEvent event() {
        ReplayPointer pointer = new ReplayPointer(
                UUID.randomUUID(),
                IdempotencyOperation.DEPOSIT,
                "a".repeat(64),
                UUID.randomUUID(),
                201
        );

        return new IdempotencyCompletedEvent("test-key", pointer);
    }

    private static double counter(SimpleMeterRegistry registry) {
        var meter = registry.find("corebank.idempotency.operations")
                .tag("outcome", "created")
                .counter();

        return meter == null ? 0.0 : meter.count();
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class TestConfig {

        @Bean
        SimpleMeterRegistry registry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        IdempotencyMetricsListener metricsListener(SimpleMeterRegistry registry) {
            return new IdempotencyMetricsListener(registry);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                private final Object resource = new Object();

                @Override
                protected Object doGetTransaction() {
                    return resource;
                }

                @Override
                protected boolean isExistingTransaction(Object transaction) {
                    return org.springframework.transaction.support
                            .TransactionSynchronizationManager
                            .isActualTransactionActive();
                }

                @Override
                protected void doBegin(
                        Object transaction,
                        org.springframework.transaction.TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}




