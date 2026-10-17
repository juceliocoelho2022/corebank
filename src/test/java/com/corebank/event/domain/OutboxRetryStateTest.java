package com.corebank.event.domain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRetryStateTest {

    @Test
    void pendingEvent_shouldStartImmediatelyEligibleWithNoFailures() {
        OutboxEvent event = pendingEvent();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
        assertThat(event.getLastErrorType()).isNull();
        assertThat(event.getLastErrorMessage()).isNull();
    }

    @Test
    void recordRetriableFailure_shouldIncrementAttemptsAndScheduleNextAttempt() {
        OutboxEvent event = pendingEvent();
        Instant nextAttemptAt = Instant.parse("2026-10-17T19:00:02Z");

        event.recordRetriableFailure("TimeoutException", "Kafka publication timed out", nextAttemptAt);

        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(event.getLastErrorType()).isEqualTo("TimeoutException");
        assertThat(event.getLastErrorMessage()).isEqualTo("Kafka publication timed out");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void markPublished_afterRetry_shouldPreserveAttemptsAndClearActiveError() {
        OutboxEvent event = pendingEvent();
        event.recordRetriableFailure(
                "TimeoutException",
                "Kafka publication timed out",
                Instant.parse("2026-10-17T19:00:02Z"));
        Instant publishedAt = Instant.parse("2026-10-17T19:00:05Z");

        event.markPublished(publishedAt);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastErrorType()).isNull();
        assertThat(event.getLastErrorMessage()).isNull();
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void markDeadLettered_shouldIncrementFailureCountAndStoreFinalFailure() {
        OutboxEvent event = pendingEvent();
        event.recordRetriableFailure(
                "TimeoutException",
                "first failure",
                Instant.parse("2026-10-17T19:00:02Z"));

        event.markDeadLettered("TimeoutException", "final failure");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getLastErrorType()).isEqualTo("TimeoutException");
        assertThat(event.getLastErrorMessage()).isEqualTo("final failure");
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void terminalEvent_shouldRejectFurtherTransitions() {
        OutboxEvent published = pendingEvent();
        published.markPublished(Instant.now());

        assertThatThrownBy(() -> published.recordRetriableFailure(
                "TimeoutException", "late failure", Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        OutboxEvent deadLettered = pendingEvent();
        deadLettered.markDeadLettered("JsonProcessingException", "invalid envelope");

        assertThatThrownBy(() -> deadLettered.markPublished(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static OutboxEvent pendingEvent() {
        UUID accountId = UUID.randomUUID();
        return OutboxEvent.pending(
                DomainEventType.DEPOSIT_COMPLETED,
                1,
                "ACCOUNT",
                accountId,
                UUID.randomUUID(),
                accountId.toString(),
                JsonNodeFactory.instance.objectNode()
                        .put("accountId", accountId.toString())
                        .put("amount", 10.00)
                        .put("currency", "BRL"));
    }
}
