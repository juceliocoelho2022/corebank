package com.corebank.event.application;

import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.infrastructure.DeadLetterEventRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeadLetterServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired DeadLetterService service;
    @Autowired DeadLetterEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void createFrom_shouldCreateExactlyOneDeadLetterForOriginalEvent() {
        OutboxEvent primary = deadLetteredPrimary();

        DeadLetterEvent first = service.createFrom(primary, "JsonProcessingException", "Non-retriable event serialization failure");
        DeadLetterEvent replay = service.createFrom(primary, "JsonProcessingException", "Non-retriable event serialization failure");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(first.toEnvelope().deadLetterId()).isEqualTo(first.getId());
        assertThat(first.toEnvelope().originalEventId()).isEqualTo(primary.getId());
        assertThat(first.toEnvelope().payload()).isEqualTo(primary.getPayload());
        assertThat(first.toEnvelope().errorType()).isEqualTo("JsonProcessingException");
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
        event.markDeadLettered("JsonProcessingException", "Non-retriable event serialization failure");
        return event;
    }
}
