package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.DeadLetterStatus;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.corebank.event.infrastructure.DeadLetterEventRepository;
import com.corebank.event.infrastructure.DeadLetterPublisher;
import com.corebank.event.infrastructure.OutboxEventRepository;
import com.corebank.event.infrastructure.OutboxPublicationWorker;
import com.corebank.transaction.application.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=true",
        "corebank.outbox.fixed-delay-ms=600000",
        "corebank.dlq.fixed-delay-ms=600000"
})
@Testcontainers(disabledWithoutDocker = true)
class RetryToDlqKafkaIT {

    private static final String PRIMARY_TOPIC = "corebank.financial-events.v1";
    private static final String DLQ_TOPIC = "corebank.financial-events.dlq.v1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired AccountService accountService;
    @Autowired CustomerRepository customerRepository;
    @Autowired TransactionService transactionService;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired DeadLetterEventRepository deadLetterRepository;
    @Autowired OutboxPublicationWorker outboxWorker;
    @Autowired DeadLetterPublisher deadLetterPublisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean(name = "kafkaTemplate")
    KafkaTemplate<Object, Object> kafkaTemplate;

    @BeforeEach
    void clean() {
        deadLetterRepository.deleteAll();
        outboxRepository.deleteAll();
    }

    @Test
    void exhaustedPrimaryEvent_shouldReachDurableDlqAndKafkaDlq() throws Exception {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Retry DLQ Customer",
                UUID.randomUUID().toString(),
                "retry-dlq." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        transactionService.deposit(account.getId(), new BigDecimal("100.00"), "private salary note");
        OutboxEvent primary = outboxRepository.findAll().stream()
                .filter(event -> event.getEventType() == DomainEventType.DEPOSIT_COMPLETED)
                .findFirst()
                .orElseThrow();

        doReturn(failedFuture(new TimeoutException("primary broker timeout")))
                .when(kafkaTemplate).send(eq(PRIMARY_TOPIC), anyString(), anyString());

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(outboxWorker.publishNext()).isTrue();
            if (attempt < 5) {
                jdbc.update(
                        "UPDATE outbox_events SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                        primary.getId());
            }
        }

        OutboxEvent exhausted = outboxRepository.findById(primary.getId()).orElseThrow();
        DeadLetterEvent deadLetter = deadLetterRepository.findByOriginalEventId(primary.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(5);
        assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(accountService.get(account.getId()).getBalance()).isEqualByComparingTo("100.00");

        assertThat(deadLetterPublisher.publishPendingBatch()).isEqualTo(1);
        ConsumerRecord<String, String> record = consumeDeadLetter(deadLetter.getId());
        JsonNode json = objectMapper.readTree(record.value());

        assertThat(record.key()).isEqualTo(account.getId().toString());
        assertThat(json.path("deadLetterId").asText()).isEqualTo(deadLetter.getId().toString());
        assertThat(json.path("originalEventId").asText()).isEqualTo(primary.getId().toString());
        assertThat(json.path("attemptCount").asInt()).isEqualTo(5);
        assertThat(json.path("errorType").asText()).isEqualTo("TimeoutException");
        assertThat(json.path("payload").path("currency").asText()).isEqualTo("BRL");
        assertThat(record.value()).doesNotContain("private salary note");
        assertThat(deadLetterRepository.findById(deadLetter.getId()).orElseThrow().getStatus())
                .isEqualTo(DeadLetterStatus.PUBLISHED);
    }

    private ConsumerRecord<String, String> consumeDeadLetter(UUID deadLetterId) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "corebank-dlq-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(DLQ_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode json = objectMapper.readTree(record.value());
                    if (deadLetterId.toString().equals(json.path("deadLetterId").asText())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("DLQ event not received: " + deadLetterId);
    }

    private static CompletableFuture<SendResult<String, String>> failedFuture(Throwable failure) {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }
}
