package com.corebank.idempotency.application;

import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.domain.IdempotencyRecord;
import com.corebank.idempotency.infrastructure.IdempotencyLockRepository;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.shared.exception.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String KEY = "payment-123";
    private static final String HASH = "a".repeat(64);

    @Mock IdempotencyRecordRepository repository;
    @Mock IdempotencyLockRepository lockRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, lockRepository, objectMapper);
    }

    @Test
    void firstExecution_shouldReserveExecuteCompleteAndReturnFreshResponse() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(lockRepository.tryLock(KEY)).thenReturn(true);
        when(repository.saveAndFlush(any(IdempotencyRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID id = UUID.randomUUID();
        TestResponse response = new TestResponse(id, "ok");
        AtomicInteger executions = new AtomicInteger();

        IdempotencyExecution<TestResponse> result = service.execute(
                KEY,
                IdempotencyOperation.DEPOSIT,
                HASH,
                TestResponse.class,
                () -> {
                    executions.incrementAndGet();
                    return new IdempotencyCommandResult<>(id, 201, response);
                });

        assertThat(result.body()).isEqualTo(response);
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(executions).hasValue(1);
        verify(repository).saveAndFlush(any(IdempotencyRecord.class));
    }

    @Test
    void completedRecord_shouldReplayWithoutExecutingCommand() {
        UUID id = UUID.randomUUID();
        TestResponse response = new TestResponse(id, "ok");
        IdempotencyRecord record = IdempotencyRecord.start(KEY, IdempotencyOperation.DEPOSIT, HASH);
        record.complete(id, 201, objectMapper.valueToTree(response));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(record));
        AtomicInteger executions = new AtomicInteger();

        IdempotencyExecution<TestResponse> result = service.execute(
                KEY,
                IdempotencyOperation.DEPOSIT,
                HASH,
                TestResponse.class,
                () -> {
                    executions.incrementAndGet();
                    return new IdempotencyCommandResult<>(id, 201, response);
                });

        assertThat(result.body()).isEqualTo(response);
        assertThat(result.replayed()).isTrue();
        assertThat(executions).hasValue(0);
        verify(lockRepository, never()).tryLock(any());
    }

    @Test
    void reusedKeyWithDifferentPayload_shouldConflict() {
        IdempotencyRecord record = IdempotencyRecord.start(KEY, IdempotencyOperation.DEPOSIT, HASH);
        record.complete(UUID.randomUUID(), 201, objectMapper.createObjectNode().put("ok", true));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.execute(
                KEY,
                IdempotencyOperation.DEPOSIT,
                "b".repeat(64),
                TestResponse.class,
                () -> null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reusedKeyWithDifferentOperation_shouldConflict() {
        IdempotencyRecord record = IdempotencyRecord.start(KEY, IdempotencyOperation.DEPOSIT, HASH);
        record.complete(UUID.randomUUID(), 201, objectMapper.createObjectNode().put("ok", true));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.execute(
                KEY,
                IdempotencyOperation.WITHDRAWAL,
                HASH,
                TestResponse.class,
                () -> null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unavailableLock_shouldFailFast() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(lockRepository.tryLock(KEY)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(
                KEY,
                IdempotencyOperation.DEPOSIT,
                HASH,
                TestResponse.class,
                () -> null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already in progress");
    }

    private record TestResponse(UUID id, String value) {
    }
}
