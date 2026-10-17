package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.corebank.event.infrastructure.OutboxEventRepository;
import com.corebank.event.infrastructure.OutboxPublisher;
import com.corebank.transaction.application.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=true",
        "corebank.outbox.fixed-delay-ms=600000"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OutboxKafkaIT {

    private static final String TOPIC = "corebank.financial-events.v1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired MockMvc mvc;
    @Autowired AccountService accountService;
    @Autowired CustomerRepository customerRepository;
    @Autowired TransactionService transactionService;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxPublisher publisher;
    @Autowired ObjectMapper objectMapper;

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Kafka Customer",
                UUID.randomUUID().toString(),
                "kafka." + UUID.randomUUID() + "@example.com"));
        Account source = accountService.create(customer.getId());
        Account destination = accountService.create(customer.getId());
        sourceId = source.getId();
        destinationId = destination.getId();
    }

    @Test
    void deposit_shouldTravelFromHttpThroughOutboxToKafka() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", sourceId)
                        .header("Idempotency-Key", "kafka-deposit-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"description\":\"private salary note\"}"))
                .andExpect(status().isCreated());

        OutboxEvent event = latestEvent(DomainEventType.DEPOSIT_COMPLETED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        assertThat(publisher.publishPendingBatch()).isEqualTo(1);
        ConsumerRecord<String, String> record = consumeEvent(event.getId());
        JsonNode json = objectMapper.readTree(record.value());

        assertThat(record.key()).isEqualTo(sourceId.toString());
        assertThat(json.path("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("DEPOSIT_COMPLETED");
        assertThat(json.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.path("aggregateType").asText()).isEqualTo("ACCOUNT");
        assertThat(json.path("aggregateId").asText()).isEqualTo(sourceId.toString());
        assertThat(json.path("payload").path("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(json.path("payload").path("currency").asText()).isEqualTo("BRL");
        assertThat(json.path("payload").has("description")).isFalse();
        assertThat(record.value()).doesNotContain("private salary note");
        assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void transfer_shouldPublishSingleTransferEventKeyedBySourceAccount() throws Exception {
        transactionService.deposit(sourceId, new java.math.BigDecimal("100.00"), "Funding");
        outboxRepository.deleteAll();

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "kafka-transfer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId":"%s",
                                  "destinationAccountId":"%s",
                                  "amount":30.00,
                                  "description":"private rent note"
                                }
                                """.formatted(sourceId, destinationId)))
                .andExpect(status().isCreated());

        OutboxEvent event = latestEvent(DomainEventType.TRANSFER_COMPLETED);
        assertThat(outboxRepository.findAll().stream()
                .filter(candidate -> candidate.getEventType() == DomainEventType.TRANSFER_COMPLETED))
                .hasSize(1);

        assertThat(publisher.publishPendingBatch()).isEqualTo(1);
        ConsumerRecord<String, String> record = consumeEvent(event.getId());
        JsonNode json = objectMapper.readTree(record.value());

        assertThat(record.key()).isEqualTo(sourceId.toString());
        assertThat(json.path("eventType").asText()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(json.path("aggregateType").asText()).isEqualTo("TRANSFER");
        assertThat(json.path("payload").path("sourceAccountId").asText()).isEqualTo(sourceId.toString());
        assertThat(json.path("payload").path("destinationAccountId").asText()).isEqualTo(destinationId.toString());
        assertThat(json.path("payload").path("amount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(json.path("payload").has("description")).isFalse();
        assertThat(record.value()).doesNotContain("private rent note");
        assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    private OutboxEvent latestEvent(DomainEventType type) {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getEventType() == type)
                .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                .orElseThrow();
    }

    private ConsumerRecord<String, String> consumeEvent(UUID eventId) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "corebank-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode json = objectMapper.readTree(record.value());
                    if (eventId.toString().equals(json.path("eventId").asText())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Kafka event not received: " + eventId);
    }
}
