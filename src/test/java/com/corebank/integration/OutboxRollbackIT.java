package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.event.infrastructure.OutboxEventRepository;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.application.RequestFingerprint;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.transaction.application.TransactionService;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxRollbackIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TransactionService transactionService;
    @Autowired IdempotencyService idempotencyService;
    @Autowired RequestFingerprint fingerprint;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired LedgerEntryRepository ledgerRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Rollback Outbox Customer",
                UUID.randomUUID().toString(),
                "rollback.outbox." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    @Test
    void failureAfterOutboxCreation_shouldRollbackEntireIdempotentFinancialTransaction() {
        String key = "outbox-rollback-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");
        String description = "rollback proof";
        long txBefore = transactionRepository.count();
        long journalsBefore = journalRepository.count();
        long ledgerBefore = ledgerRepository.count();
        long outboxBefore = outboxRepository.count();

        assertThatThrownBy(() -> idempotencyService.execute(
                key,
                IdempotencyOperation.DEPOSIT,
                fingerprint.deposit(accountId, amount, description),
                TestResponse.class,
                () -> {
                    transactionService.deposit(accountId, amount, description);
                    throw new IllegalStateException("forced failure after outbox");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced failure after outbox");

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(transactionRepository.count()).isEqualTo(txBefore);
        assertThat(journalRepository.count()).isEqualTo(journalsBefore);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerBefore);
        assertThat(outboxRepository.count()).isEqualTo(outboxBefore);
        assertThat(idempotencyRepository.findByIdempotencyKey(key)).isEmpty();
    }

    private record TestResponse(UUID id) {
    }
}
