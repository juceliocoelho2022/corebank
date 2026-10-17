package com.corebank.event.infrastructure;

import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxRetryWorkerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired OutboxEventRepository repository;
    @Autowired DeadLetterEventRepository deadLetterRepository;
    @Autowired OutboxPublicationWorker worker;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clean() {
        deadLetterRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void retriableFailures_shouldPersistExactBackoffAndDeadLetterOnFifthFailure() {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture(new TimeoutException("broker unavailable")));

        long[] expectedSeconds = {2, 5, 15, 30};
        for (int attempt = 1; attempt <= 4; attempt++) {
            Instant before = Instant.now();
            assertThat(worker.publishNext()).isTrue();
            Instant after = Instant.now();

            OutboxEvent reloaded = repository.findById(event.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(reloaded.getAttemptCount()).isEqualTo(attempt);
            assertThat(reloaded.getLastErrorType()).isEqualTo("TimeoutException");
            assertThat(reloaded.getLastErrorMessage()).isEqualTo("Transient Kafka publication failure");
            assertThat(reloaded.getNextAttemptAt())
                    .isBetween(before.plusSeconds(expectedSeconds[attempt - 1]),
                            after.plusSeconds(expectedSeconds[attempt - 1]).plusMillis(500));

            makeEligible(event.getId());
        }

        assertThat(worker.publishNext()).isTrue();

        OutboxEvent exhausted = repository.findById(event.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(5);
        assertThat(deadLetterRepository.findByOriginalEventId(event.getId())).isPresent();
        assertThat(deadLetterRepository.count()).isEqualTo(1);
    }

    @Test
    void retryThenSuccess_shouldPreserveAttemptCountAndClearActiveError() {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        AtomicInteger calls = new AtomicInteger();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return failedFuture(new TimeoutException("temporary"));
            }
            return CompletableFuture.completedFuture(null);
        });

        assertThat(worker.publishNext()).isTrue();
        makeEligible(event.getId());
        assertThat(worker.publishNext()).isTrue();

        OutboxEvent reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLastErrorType()).isNull();
        assertThat(reloaded.getLastErrorMessage()).isNull();
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void eventWaitingForBackoff_shouldNotBlockNewerEligibleEvent() {
        OutboxEvent first = repository.saveAndFlush(pendingEvent());
        AtomicInteger calls = new AtomicInteger();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return failedFuture(new TimeoutException("temporary"));
            }
            return CompletableFuture.completedFuture(null);
        });

        assertThat(worker.publishNext()).isTrue();
        OutboxEvent second = repository.saveAndFlush(pendingEvent());

        assertThat(worker.publishNext()).isTrue();

        assertThat(repository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(repository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    private void makeEligible(UUID eventId) {
        jdbc.update(
                "UPDATE outbox_events SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                eventId);
    }

    private static CompletableFuture failedFuture(Throwable failure) {
        CompletableFuture future = new CompletableFuture();
        future.completeExceptionally(failure);
        return future;
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
