package com.corebank.event.infrastructure;

import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired OutboxEventRepository repository;
    @Autowired OutboxPublicationWorker worker;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void secondWorker_shouldSkipLockedFirstRowAndPublishNextRow() throws Exception {
        OutboxEvent first = repository.saveAndFlush(pendingEvent());
        Thread.sleep(5);
        OutboxEvent second = repository.saveAndFlush(pendingEvent());

        CompletableFuture<SendResult<String, String>> firstAck = new CompletableFuture<>();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();

        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (sends.incrementAndGet() == 1) {
                firstSendStarted.countDown();
                return firstAck;
            }
            return CompletableFuture.completedFuture(null);
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(worker::publishNext);
            assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var secondResult = executor.submit(worker::publishNext);
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isTrue();

            firstAck.complete(null);
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(repository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(sends).hasValue(2);
    }

    @Test
    void secondWorker_shouldReturnFalseWhenOnlyPendingRowIsLocked() throws Exception {
        OutboxEvent event = repository.saveAndFlush(pendingEvent());
        CompletableFuture<SendResult<String, String>> firstAck = new CompletableFuture<>();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();

        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sends.incrementAndGet();
            firstSendStarted.countDown();
            return firstAck;
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(worker::publishNext);
            assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var secondResult = executor.submit(worker::publishNext);
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isFalse();

            firstAck.complete(null);
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(sends).hasValue(1);
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
                        .put("amount", 10.00)
                        .put("currency", "BRL"));
    }
}
