package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RedisUnavailableIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired CustomerRepository customers;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired AccountTransactionRepository transactions;
    @Autowired JournalEntryRepository journals;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired IdempotencyRecordRepository idempotency;

    private UUID customerId;
    private UUID fromId;
    private UUID toId;

    @BeforeEach
    void setup() {
        Customer customer = customers.saveAndFlush(new Customer("Offline Redis",
                UUID.randomUUID().toString(), "offline." + UUID.randomUUID() + "@example.com"));
        customerId = customer.getId();
        Account source = accountService.create(customerId);
        Account destination = accountService.create(customerId);
        fromId = source.getId();
        toId = destination.getId();
    }

    @Test
    void redisDownDoesNotBreakHealthSummariesOrExactOnceFinancialCommands() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/api/v1/customers/{id}/summary", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Offline Redis"))
                .andExpect(jsonPath("$.document").doesNotExist());
        mvc.perform(get("/api/v1/accounts/{id}/summary", fromId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").doesNotExist());

        long beforeTransactions = transactions.count();
        long beforeJournals = journals.count();
        long beforeEntries = ledgerEntries.count();
        long beforeIdempotency = idempotency.count();

        String depositKey = "offline-deposit-" + UUID.randomUUID();
        String depositBody = "{\"amount\":100.00}";
        mvc.perform(post("/api/v1/accounts/{id}/deposits", fromId)
                        .header("Idempotency-Key", depositKey)
                        .contentType(MediaType.APPLICATION_JSON).content(depositBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(post("/api/v1/accounts/{id}/deposits", fromId)
                        .header("Idempotency-Key", depositKey)
                        .contentType(MediaType.APPLICATION_JSON).content(depositBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        String withdrawKey = "offline-withdraw-" + UUID.randomUUID();
        String withdrawBody = "{\"amount\":20.00}";
        mvc.perform(post("/api/v1/accounts/{id}/withdrawals", fromId)
                        .header("Idempotency-Key", withdrawKey)
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(post("/api/v1/accounts/{id}/withdrawals", fromId)
                        .header("Idempotency-Key", withdrawKey)
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        String transferKey = "offline-transfer-" + UUID.randomUUID();
        String transferBody = "{\"sourceAccountId\":\"" + fromId
                + "\",\"destinationAccountId\":\"" + toId + "\",\"amount\":10.00}";
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", transferKey)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", transferKey)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(accounts.findById(fromId).orElseThrow().getBalance()).isEqualByComparingTo("70.00");
        assertThat(accounts.findById(toId).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(transactions.count() - beforeTransactions).isEqualTo(4);
        assertThat(journals.count() - beforeJournals).isEqualTo(3);
        assertThat(ledgerEntries.count() - beforeEntries).isEqualTo(6);
        assertThat(idempotency.count() - beforeIdempotency).isEqualTo(3);

        mvc.perform(get("/api/v1/accounts/{id}/balance", fromId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(70.00));
    }
}
