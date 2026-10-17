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
class DeadLetterConcurrencyIT {

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
    void secondWorker_shouldSkipLockedFirstDeadLetterAndPublishNextRow() throws Exception {
        DeadLetterEvent first = repository.saveAndFlush(deadLetter());
        Thread.sleep(5);
        DeadLetterEvent second = repository.saveAndFlush(deadLetter());

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
                .isEqualTo(DeadLetterStatus.PUBLISHED);
        assertThat(repository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(DeadLetterStatus.PUBLISHED);
        assertThat(sends).hasValue(2);
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
                        .put("amount", 10.00)
                        .put("currency", "BRL"));
        primary.markDeadLettered("TimeoutException", "Transient Kafka publication failure");
        return DeadLetterEvent.from(primary, primary.getLastErrorType(), primary.getLastErrorMessage());
    }
}
