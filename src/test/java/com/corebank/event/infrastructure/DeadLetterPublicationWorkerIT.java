package com.corebank.event.infrastructure;

import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.DeadLetterStatus;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
class DeadLetterPublicationWorkerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired DeadLetterEventRepository repository;
    @Autowired DeadLetterPublicationWorker worker;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void successfulAck_shouldPublishExplicitEnvelopeAndMarkRowPublished() throws Exception {
        DeadLetterEvent event = repository.saveAndFlush(deadLetter());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(worker.publishNext()).isTrue();

        DeadLetterEvent reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeadLetterStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("corebank.financial-events.dlq.v1"),
                org.mockito.ArgumentMatchers.eq(event.getMessageKey()),
                captor.capture());
        JsonNode json = objectMapper.readTree(captor.getValue());
        assertThat(json.path("deadLetterId").asText()).isEqualTo(event.getId().toString());
        assertThat(json.path("originalEventId").asText()).isEqualTo(event.getOriginalEventId().toString());
        assertThat(json.path("errorType").asText()).isEqualTo("TimeoutException");
        assertThat(json.path("payload").path("currency").asText()).isEqualTo("BRL");
    }

    @Test
    void repeatedKafkaFailures_shouldPersistAttemptsAndCapBackoffAtSixtySeconds() {
        DeadLetterEvent event = repository.saveAndFlush(deadLetter());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture(new TimeoutException("dlq unavailable")));

        long[] expectedSeconds = {5, 15, 30, 60, 60};
        String originalType = event.getLastErrorType();
        String originalMessage = event.getLastErrorMessage();

        for (int attempt = 1; attempt <= expectedSeconds.length; attempt++) {
            Instant before = Instant.now();
            assertThat(worker.publishNext()).isTrue();
            Instant after = Instant.now();

            DeadLetterEvent reloaded = repository.findById(event.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
            assertThat(reloaded.getPublishAttempts()).isEqualTo(attempt);
            assertThat(reloaded.getLastPublishErrorType()).isEqualTo("TimeoutException");
            assertThat(reloaded.getLastPublishErrorMessage()).isEqualTo("Transient Kafka publication failure");
            assertThat(reloaded.getLastErrorType()).isEqualTo(originalType);
            assertThat(reloaded.getLastErrorMessage()).isEqualTo(originalMessage);
            assertThat(reloaded.getNextAttemptAt())
                    .isBetween(before.plusSeconds(expectedSeconds[attempt - 1]),
                            after.plusSeconds(expectedSeconds[attempt - 1]).plusMillis(500));
            assertThat(repository.count()).isEqualTo(1);

            makeEligible(event.getId());
        }
    }

    private void makeEligible(UUID id) {
        jdbc.update(
                "UPDATE dead_letter_events SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                id);
    }

    private static CompletableFuture failedFuture(Throwable failure) {
        CompletableFuture future = new CompletableFuture();
        future.completeExceptionally(failure);
        return future;
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
