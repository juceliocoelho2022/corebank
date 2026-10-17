package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyExecution;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.shared.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired IdempotencyService idempotencyService;
    @Autowired IdempotencyRecordRepository repository;

    @Test
    void concurrentSameKey_shouldExecuteCommandOnlyOnce() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        String hash = "a".repeat(64);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch commandStarted = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<IdempotencyExecution<TestResponse>> first = executor.submit(() -> idempotencyService.execute(
                    key,
                    IdempotencyOperation.DEPOSIT,
                    hash,
                    TestResponse.class,
                    () -> {
                        executions.incrementAndGet();
                        commandStarted.countDown();
                        await(releaseCommand);
                        UUID id = UUID.randomUUID();
                        return new IdempotencyCommandResult<>(id, 201, new TestResponse(id, "first"));
                    }));

            assertThat(commandStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> second = executor.submit(() -> {
                try {
                    idempotencyService.execute(
                            key,
                            IdempotencyOperation.DEPOSIT,
                            hash,
                            TestResponse.class,
                            () -> {
                                executions.incrementAndGet();
                                UUID id = UUID.randomUUID();
                                return new IdempotencyCommandResult<>(id, 201, new TestResponse(id, "second"));
                            });
                    return null;
                } catch (Throwable ex) {
                    return ex;
                }
            });

            Throwable concurrentResult = second.get(10, TimeUnit.SECONDS);
            assertThat(concurrentResult).isInstanceOf(ConflictException.class);
            assertThat(executions).hasValue(1);

            releaseCommand.countDown();
            IdempotencyExecution<TestResponse> completed = first.get(10, TimeUnit.SECONDS);
            assertThat(completed.replayed()).isFalse();
            assertThat(repository.findByIdempotencyKey(key)).isPresent();
        } finally {
            releaseCommand.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrency test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrency test latch", ex);
        }
    }

    private record TestResponse(UUID id, String value) {
    }
}
