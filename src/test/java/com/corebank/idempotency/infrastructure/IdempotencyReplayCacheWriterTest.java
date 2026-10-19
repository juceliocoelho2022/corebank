package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.application.IdempotencyCompletedEvent;
import com.corebank.idempotency.domain.IdempotencyOperation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IdempotencyReplayCacheWriterTest {
    @Test
    void writesOnlyCompletedPointerViaAfterCommitListener() throws Exception {
        var cache = mock(IdempotencyReplayCache.class);
        var pointer = new ReplayPointer(UUID.randomUUID(), IdempotencyOperation.DEPOSIT, "a".repeat(64), null, 201);
        new IdempotencyReplayCacheWriter(cache).onCompleted(new IdempotencyCompletedEvent("some-key-123", pointer));
        verify(cache).putCompleted("some-key-123", pointer);
        var method = IdempotencyReplayCacheWriter.class.getDeclaredMethod("onCompleted", IdempotencyCompletedEvent.class);
        var annotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }
}
