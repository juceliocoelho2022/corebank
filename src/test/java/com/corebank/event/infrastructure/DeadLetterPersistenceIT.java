package com.corebank.event.infrastructure;

import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.DeadLetterStatus;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeadLetterPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired DeadLetterEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void repository_shouldPersistCompleteDeadLetterSnapshot() {
        OutboxEvent primary = deadLetteredPrimary();
        DeadLetterEvent persisted = repository.saveAndFlush(DeadLetterEvent.from(
                primary,
                primary.getLastErrorType(),
                primary.getLastErrorMessage()));

        DeadLetterEvent reloaded = repository.findById(persisted.getId()).orElseThrow();

        assertThat(reloaded.getOriginalEventId()).isEqualTo(primary.getId());
        assertThat(reloaded.getEventType()).isEqualTo(primary.getEventType());
        assertThat(reloaded.getEventVersion()).isEqualTo(primary.getEventVersion());
        assertThat(reloaded.getAggregateType()).isEqualTo(primary.getAggregateType());
        assertThat(reloaded.getAggregateId()).isEqualTo(primary.getAggregateId());
        assertThat(reloaded.getCorrelationId()).isEqualTo(primary.getCorrelationId());
        assertThat(reloaded.getMessageKey()).isEqualTo(primary.getMessageKey());
        assertThat(reloaded.getPayload()).isEqualTo(primary.getPayload());
        assertThat(reloaded.getAttemptCount()).isEqualTo(primary.getAttemptCount());
        assertThat(reloaded.getLastErrorType()).isEqualTo("TimeoutException");
        assertThat(reloaded.getLastErrorMessage()).isEqualTo("Transient Kafka publication failure");
        assertThat(reloaded.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(reloaded.getPublishAttempts()).isZero();
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(reloaded.getCreatedAt());
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getLastPublishErrorType()).isNull();
        assertThat(reloaded.getLastPublishErrorMessage()).isNull();
        assertThat(reloaded.getPayload().has("description")).isFalse();
        assertThat(reloaded.getPayload().has("idempotencyKey")).isFalse();
    }

    @Test
    void database_shouldEnforceUniqueOriginalEventId() {
        OutboxEvent primary = deadLetteredPrimary();
        repository.saveAndFlush(DeadLetterEvent.from(primary, "TimeoutException", "Transient Kafka publication failure"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                DeadLetterEvent.from(primary, "TimeoutException", "Transient Kafka publication failure")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static OutboxEvent deadLetteredPrimary() {
        UUID accountId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                DomainEventType.DEPOSIT_COMPLETED,
                1,
                "ACCOUNT",
                accountId,
                UUID.randomUUID(),
                accountId.toString(),
                JsonNodeFactory.instance.objectNode()
                        .put("accountId", accountId.toString())
                        .put("transactionId", UUID.randomUUID().toString())
                        .put("amount", 10.00)
                        .put("currency", "BRL"));
        event.markDeadLettered("TimeoutException", "Transient Kafka publication failure");
        return event;
    }
}
