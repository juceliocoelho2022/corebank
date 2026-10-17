package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.transaction.application.TransactionService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TransferIdempotencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired AccountService accountService;
    @Autowired TransactionService transactionService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        Customer sourceCustomer = customerRepository.saveAndFlush(new Customer(
                "Transfer Source",
                UUID.randomUUID().toString(),
                "source." + UUID.randomUUID() + "@example.com"));
        Customer destinationCustomer = customerRepository.saveAndFlush(new Customer(
                "Transfer Destination",
                UUID.randomUUID().toString(),
                "destination." + UUID.randomUUID() + "@example.com"));

        Account source = accountService.create(sourceCustomer.getId());
        Account destination = accountService.create(destinationCustomer.getId());
        sourceId = source.getId();
        destinationId = destination.getId();
        transactionService.deposit(sourceId, new BigDecimal("500.00"), "seed");
    }

    @Test
    void repeatedTransfer_shouldApplyDebitCreditHistoryAndLedgerExactlyOnce() throws Exception {
        String key = "transfer-" + UUID.randomUUID();
        long txBefore = transactionRepository.count();
        long journalBefore = journalRepository.count();
        long ledgerBefore = ledgerEntryRepository.count();
        long idempotencyBefore = idempotencyRepository.count();

        String body = "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":125.00,\"description\":\"Invoice\"}"
                .formatted(sourceId, destinationId);

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(accountRepository.findById(sourceId).orElseThrow().getBalance())
                .isEqualByComparingTo("375.00");
        assertThat(accountRepository.findById(destinationId).orElseThrow().getBalance())
                .isEqualByComparingTo("125.00");
        assertThat(transactionRepository.count() - txBefore).isEqualTo(2);
        assertThat(journalRepository.count() - journalBefore).isEqualTo(1);
        assertThat(ledgerEntryRepository.count() - ledgerBefore).isEqualTo(2);
        assertThat(idempotencyRepository.count() - idempotencyBefore).isEqualTo(1);
    }

    @Test
    void sameTransferKeyWithDifferentAmount_shouldConflictWithoutDuplicateTransfer() throws Exception {
        String key = "transfer-conflict-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("50.00")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("75.00")))
                .andExpect(status().isConflict());

        assertThat(accountRepository.findById(sourceId).orElseThrow().getBalance())
                .isEqualByComparingTo("450.00");
        assertThat(accountRepository.findById(destinationId).orElseThrow().getBalance())
                .isEqualByComparingTo("50.00");
    }

    private String body(String amount) {
        return "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":%s}"
                .formatted(sourceId, destinationId, amount);
    }
}
