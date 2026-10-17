package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.infrastructure.OutboxEventRepository;
import com.corebank.transaction.application.TransactionService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OutboxIdempotencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired AccountService accountService;
    @Autowired CustomerRepository customerRepository;
    @Autowired TransactionService transactionService;
    @Autowired OutboxEventRepository outboxRepository;

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Replay Customer",
                UUID.randomUUID().toString(),
                "replay." + UUID.randomUUID() + "@example.com"));
        Account source = accountService.create(customer.getId());
        Account destination = accountService.create(customer.getId());
        sourceId = source.getId();
        destinationId = destination.getId();
    }

    @Test
    void depositReplay_shouldNotCreateSecondOutboxEvent() throws Exception {
        String key = "outbox-deposit-" + UUID.randomUUID();
        long before = outboxRepository.count();

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", sourceId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"description\":\"Salary\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", sourceId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.0,\"description\":\" Salary \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(outboxRepository.count()).isEqualTo(before + 1);
        assertThat(outboxRepository.findAll().stream()
                .filter(event -> event.getEventType() == DomainEventType.DEPOSIT_COMPLETED)
                .filter(event -> event.getAggregateId().equals(sourceId)))
                .hasSize(1);
    }

    @Test
    void changedPayloadWithSameKey_shouldConflictWithoutSecondOutboxEvent() throws Exception {
        String key = "outbox-conflict-" + UUID.randomUUID();
        long before = outboxRepository.count();

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", sourceId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25.00}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", sourceId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30.00}"))
                .andExpect(status().isConflict());

        assertThat(outboxRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void transferReplay_shouldKeepSingleTransferCompletedEvent() throws Exception {
        transactionService.deposit(sourceId, new BigDecimal("100.00"), "Funding");
        long before = outboxRepository.count();
        String key = "outbox-transfer-" + UUID.randomUUID();
        String payload = """
                {
                  "sourceAccountId":"%s",
                  "destinationAccountId":"%s",
                  "amount":30.00,
                  "description":"Rent"
                }
                """.formatted(sourceId, destinationId);

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(outboxRepository.count()).isEqualTo(before + 1);
        assertThat(outboxRepository.findAll().stream()
                .filter(event -> event.getEventType() == DomainEventType.TRANSFER_COMPLETED)
                .filter(event -> event.getMessageKey().equals(sourceId.toString())))
                .hasSize(1);
    }
}
