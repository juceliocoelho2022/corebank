package com.corebank.event.infrastructure;

import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxDeadLetterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired OutboxEventRepository repository;
    @Autowired DeadLetterEventRepository deadLetterRepository;
    @Autowired OutboxPublicationWorker worker;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clean() {
        deadLetterRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void nonRetriableSerializationFailure_shouldDeadLetterImmediately() {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new SerializationException("payload must never be persisted in error"));

        assertThat(worker.publishNext()).isTrue();

        OutboxEvent primary = repository.findById(event.getId()).orElseThrow();
        DeadLetterEvent deadLetter = deadLetterRepository.findByOriginalEventId(event.getId()).orElseThrow();

        assertThat(primary.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        assertThat(primary.getAttemptCount()).isEqualTo(1);
        assertThat(primary.getLastErrorType()).isEqualTo("SerializationException");
        assertThat(primary.getLastErrorMessage()).isEqualTo("Non-retriable event serialization failure");
        assertThat(deadLetter.getAttemptCount()).isEqualTo(1);
        assertThat(deadLetter.getLastErrorType()).isEqualTo("SerializationException");
        assertThat(deadLetterRepository.count()).isEqualTo(1);
        assertThat(primary.getLastErrorMessage()).doesNotContain("payload must never");
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
                        .put("transactionId", UUID.randomUUID().toString())
                        .put("amount", 100.00)
                        .put("currency", "BRL"));
    }
}
