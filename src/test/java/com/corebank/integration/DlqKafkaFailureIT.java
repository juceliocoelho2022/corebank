package com.corebank.integration;

import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.DeadLetterStatus;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.infrastructure.DeadLetterEventRepository;
import com.corebank.event.infrastructure.DeadLetterPublicationWorker;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kafka.common.errors.TimeoutException;
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

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DlqKafkaFailureIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired DeadLetterEventRepository repository;
    @Autowired DeadLetterPublicationWorker worker;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void dlqBrokerFailure_shouldRemainDurableAndScheduleRetry() {
        DeadLetterEvent event = repository.saveAndFlush(deadLetter());
        CompletableFuture failed = new CompletableFuture();
        failed.completeExceptionally(new TimeoutException("dlq broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);
        Instant before = Instant.now();

        assertThat(worker.publishNext()).isTrue();

        DeadLetterEvent reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(reloaded.getPublishAttempts()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(5));
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(repository.count()).isEqualTo(1);
    }

    private static DeadLetterEvent deadLetter() {
        UUID accountId = UUID.randomUUID();
        OutboxEvent primary = OutboxEvent.pending(
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
        primary.markDeadLettered("TimeoutException", "Transient Kafka publication failure");
        return DeadLetterEvent.from(primary, primary.getLastErrorType(), primary.getLastErrorMessage());
    }
}
