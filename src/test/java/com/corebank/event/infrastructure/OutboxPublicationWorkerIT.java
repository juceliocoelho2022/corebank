package com.corebank.event.infrastructure;

import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublicationWorkerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired OutboxEventRepository repository;
    @Autowired OutboxPublicationWorker worker;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void successfulAck_shouldPublishExactEnvelopeAndMarkRowPublished() throws Exception {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(worker.publishNext()).isTrue();

        OutboxEvent reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("corebank.financial-events.v1"),
                org.mockito.ArgumentMatchers.eq(event.getMessageKey()),
                captor.capture());
        JsonNode json = objectMapper.readTree(captor.getValue());
        assertThat(json.path("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("DEPOSIT_COMPLETED");
        assertThat(json.path("aggregateId").asText()).isEqualTo(event.getAggregateId().toString());
        assertThat(json.path("payload").path("currency").asText()).isEqualTo("BRL");
    }

    @Test
    void failedKafkaSend_shouldPersistRetryState() {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        CompletableFuture failed = new CompletableFuture();
        failed.completeExceptionally(new TimeoutException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        assertThat(worker.publishNext()).isTrue();

        OutboxEvent reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLastErrorType()).isEqualTo("TimeoutException");
        assertThat(reloaded.getPublishedAt()).isNull();
    }

    @Test
    void rowMustRemainPendingUntilKafkaFutureAcknowledges() throws Exception {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        CompletableFuture kafkaAck = new CompletableFuture();
        CountDownLatch sendStarted = new CountDownLatch(1);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return kafkaAck;
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(worker::publishNext);
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            OutboxEvent whileWaiting = repository.findById(event.getId()).orElseThrow();
            assertThat(whileWaiting.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(whileWaiting.getPublishedAt()).isNull();

            kafkaAck.complete(null);
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
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
