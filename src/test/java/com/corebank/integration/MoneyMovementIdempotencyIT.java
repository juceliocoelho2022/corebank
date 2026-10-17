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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MoneyMovementIdempotencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Idempotent Customer",
                UUID.randomUUID().toString(),
                "idem." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    @Test
    void repeatedDeposit_shouldApplyFinancialEffectsExactlyOnce() throws Exception {
        String key = "deposit-" + UUID.randomUUID();
        long txBefore = transactionRepository.count();
        long journalBefore = journalRepository.count();
        long ledgerBefore = ledgerEntryRepository.count();
        long idempotencyBefore = idempotencyRepository.count();

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"description\":\"Salary\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.0,\"description\":\" Salary \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.count() - txBefore).isEqualTo(1);
        assertThat(journalRepository.count() - journalBefore).isEqualTo(1);
        assertThat(ledgerEntryRepository.count() - ledgerBefore).isEqualTo(2);
        assertThat(idempotencyRepository.count() - idempotencyBefore).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayload_shouldReturnConflictWithoutSecondEffect() throws Exception {
        String key = "deposit-conflict-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25.00}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30.00}"))
                .andExpect(status().isConflict());

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("25.00");
    }

    @Test
    void failedWithdrawal_shouldRollbackIdempotencyReservation() throws Exception {
        String key = "withdraw-failed-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", accountId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isConflict());

        assertThat(idempotencyRepository.findByIdempotencyKey(key)).isEmpty();
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }
}
