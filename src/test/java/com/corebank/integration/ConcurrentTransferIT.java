package com.corebank.integration;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import com.corebank.transfer.application.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentTransferIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountTransactionRepository transactionRepository;

    private UUID sourceId;
    private UUID secondSourceId;
    private UUID firstDestinationId;
    private UUID secondDestinationId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        Account source = new Account(customer.getId(), "10006661");
        source.deposit(new BigDecimal("100.00"));
        sourceId = accountRepository.saveAndFlush(source).getId();
        Account secondSource = new Account(customer.getId(), "10006664");
        secondSource.deposit(new BigDecimal("100.00"));
        secondSourceId = accountRepository.saveAndFlush(secondSource).getId();
        firstDestinationId = accountRepository.saveAndFlush(new Account(customer.getId(), "10006662")).getId();
        secondDestinationId = accountRepository.saveAndFlush(new Account(customer.getId(), "10006663")).getId();
    }

    @Test
    void concurrentTransfersFromSameSource_shouldNeverDoubleSpend() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<String> first = transfer(start, firstDestinationId);
        Callable<String> second = transfer(start, secondDestinationId);
        Future<String> firstResult = pool.submit(first);
        Future<String> secondResult = pool.submit(second);
        start.countDown();

        List<String> outcomes = List.of(firstResult.get(), secondResult.get());
        pool.shutdownNow();

        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        assertThat(accountRepository.findById(sourceId).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
        long sourceOut = transactionRepository.findByAccountIdOrderByCreatedAtDesc(sourceId).stream()
                .filter(tx -> tx.getType().name().equals("TRANSFER_OUT"))
                .count();
        assertThat(sourceOut).isEqualTo(1);
    }

    @Test
    void concurrentTransfersToSameDestination_shouldNeverLoseACredit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<String> firstResult = pool.submit(transferFrom(start, sourceId, firstDestinationId, new BigDecimal("40.00")));
        Future<String> secondResult = pool.submit(transferFrom(start, secondSourceId, firstDestinationId, new BigDecimal("40.00")));
        start.countDown();

        List<String> outcomes = List.of(firstResult.get(), secondResult.get());
        pool.shutdownNow();

        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        assertThat(accountRepository.findById(firstDestinationId).orElseThrow().getBalance()).isEqualByComparingTo("40.00");
        long destinationIn = transactionRepository.findByAccountIdOrderByCreatedAtDesc(firstDestinationId).stream()
                .filter(tx -> tx.getType().name().equals("TRANSFER_IN"))
                .count();
        assertThat(destinationIn).isEqualTo(1);
    }

    private Callable<String> transfer(CountDownLatch start, UUID destinationId) {
        return transferFrom(start, sourceId, destinationId, new BigDecimal("80.00"));
    }

    private Callable<String> transferFrom(CountDownLatch start, UUID fromId, UUID destinationId, BigDecimal amount) {
        return () -> {
            await(start);
            try {
                transferService.transfer(fromId, destinationId, amount, "concurrent");
                return "SUCCESS";
            } catch (ObjectOptimisticLockingFailureException ex) {
                return "CONFLICT";
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof ObjectOptimisticLockingFailureException) return "CONFLICT";
                if (ex.getClass().getSimpleName().contains("Optimistic")) return "CONFLICT";
                if (ex.getMessage() != null && ex.getMessage().contains("Insufficient balance")) return "CONFLICT";
                throw ex;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrency test", ex);
        }
    }
}
