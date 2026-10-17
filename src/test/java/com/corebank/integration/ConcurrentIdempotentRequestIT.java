package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyExecution;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.shared.exception.ConflictException;
import com.corebank.transaction.application.TransactionService;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import com.corebank.transaction.web.TransactionResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentIdempotentRequestIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TransactionService transactionService;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired IdempotencyService idempotencyService;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Concurrent Customer",
                UUID.randomUUID().toString(),
                "concurrent." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    @Test
    void overlappingSameKey_shouldExecuteFinancialEffectsExactlyOnce() throws Exception {
        String key = "financial-concurrent-" + UUID.randomUUID();
        String hash = "e".repeat(64);
        AtomicInteger financialExecutions = new AtomicInteger();
        CountDownLatch commandStarted = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        long txBefore = transactionRepository.count();
        long journalBefore = journalRepository.count();
        long ledgerBefore = ledgerEntryRepository.count();
        long idempotencyBefore = idempotencyRepository.count();

        try {
            Future<IdempotencyExecution<TransactionResponse>> first = executor.submit(() -> idempotencyService.execute(
                    key,
                    IdempotencyOperation.DEPOSIT,
                    hash,
                    TransactionResponse.class,
                    () -> {
                        financialExecutions.incrementAndGet();
                        commandStarted.countDown();
                        await(releaseCommand);
                        var persisted = transactionService.deposit(accountId, new BigDecimal("100.00"), "concurrent proof");
                        TransactionResponse response = TransactionResponse.from(persisted);
                        return new IdempotencyCommandResult<>(response.transactionId(), 201, response);
                    }));

            assertThat(commandStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> second = executor.submit(() -> {
                try {
                    idempotencyService.execute(
                            key,
                            IdempotencyOperation.DEPOSIT,
                            hash,
                            TransactionResponse.class,
                            () -> {
                                financialExecutions.incrementAndGet();
                                var persisted = transactionService.deposit(accountId, new BigDecimal("100.00"), "concurrent proof");
                                TransactionResponse response = TransactionResponse.from(persisted);
                                return new IdempotencyCommandResult<>(response.transactionId(), 201, response);
                            });
                    return null;
                } catch (Throwable ex) {
                    return ex;
                }
            });

            Throwable concurrentResult = second.get(10, TimeUnit.SECONDS);
            assertThat(concurrentResult).isInstanceOf(ConflictException.class);
            assertThat(financialExecutions).hasValue(1);

            releaseCommand.countDown();
            IdempotencyExecution<TransactionResponse> completed = first.get(10, TimeUnit.SECONDS);
            assertThat(completed.replayed()).isFalse();

            assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                    .isEqualByComparingTo("100.00");
            assertThat(transactionRepository.count() - txBefore).isEqualTo(1);
            assertThat(journalRepository.count() - journalBefore).isEqualTo(1);
            assertThat(ledgerEntryRepository.count() - ledgerBefore).isEqualTo(2);
            assertThat(idempotencyRepository.count() - idempotencyBefore).isEqualTo(1);
        } finally {
            releaseCommand.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent financial command");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent financial command", ex);
        }
    }
}
