package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.shared.exception.InsufficientBalanceException;
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
class MoneyMovementIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired TransactionService transactionService;
    @Autowired AccountService accountService;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva",
                UUID.randomUUID().toString(),
                "ana." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    @Test void deposit_shouldCommitBalanceAndExactlyOneHistoryRow() {
        transactionService.deposit(accountId, new BigDecimal("100.00"), "Initial deposit");
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).hasSize(1);
    }

    @Test void withdrawal_shouldCommitBalanceAndExactlyOneHistoryRow() {
        transactionService.deposit(accountId, new BigDecimal("100.00"), "seed");
        transactionRepository.deleteAll();
        transactionService.withdraw(accountId, new BigDecimal("30.00"), "ATM");
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("70.00");
        assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).hasSize(1);
    }

    @Test void rejectedWithdrawal_shouldLeaveBalanceAndHistoryUnchanged() {
        transactionService.deposit(accountId, new BigDecimal("20.00"), "seed");
        transactionRepository.deleteAll();
        assertThatThrownBy(() -> transactionService.withdraw(accountId, new BigDecimal("50.00"), null))
                .isInstanceOf(InsufficientBalanceException.class);
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("20.00");
        assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).isEmpty();
    }

    @Test void transactionInsertFailure_shouldRollbackBalanceChange() {
        String invalidDescription = "x".repeat(201);
        assertThatThrownBy(() -> transactionService.deposit(accountId, new BigDecimal("100.00"), invalidDescription))
                .isInstanceOf(IllegalArgumentException.class);
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("0.00");
        assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).isEmpty();
    }
}
