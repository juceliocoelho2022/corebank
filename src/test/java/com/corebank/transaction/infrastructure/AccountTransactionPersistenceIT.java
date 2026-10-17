package com.corebank.transaction.infrastructure;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountTransactionPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    AccountTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = customerRepository.saveAndFlush(
                new Customer("Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com")
        );

        accountId = accountRepository.saveAndFlush(new Account(customer.getId(), "10009991")).getId();
    }

    @Test
    void flywayV2_shouldPersistValidTransaction() {
        AccountTransaction saved = transactionRepository.saveAndFlush(
                new AccountTransaction(
                        accountId,
                        TransactionType.DEPOSIT,
                        new BigDecimal("100.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("100.00"),
                        " Initial deposit "
                )
        );

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getDescription()).isEqualTo("Initial deposit");
        assertThat(saved.getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void database_shouldRejectZeroOrNegativeTransactionAmount() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO account_transactions (id, account_id, type, amount, balance_before, balance_after, created_at) VALUES (?, ?, 'DEPOSIT', 0.00, 0.00, 0.00, ?)",
                UUID.randomUUID(),
                accountId,
                OffsetDateTime.now(ZoneOffset.UTC)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_shouldRejectInvalidTypeOrNegativeBalances() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO account_transactions (id, account_id, type, amount, balance_before, balance_after, created_at) VALUES (?, ?, 'UNKNOWN', 10.00, 0.00, 10.00, ?)",
                UUID.randomUUID(),
                accountId,
                OffsetDateTime.now(ZoneOffset.UTC)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO account_transactions (id, account_id, type, amount, balance_before, balance_after, created_at) VALUES (?, ?, 'WITHDRAWAL', 10.00, -1.00, 0.00, ?)",
                UUID.randomUUID(),
                accountId,
                OffsetDateTime.now(ZoneOffset.UTC)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void history_shouldReturnNewestTransactionFirst() throws InterruptedException {
        transactionRepository.saveAndFlush(
                new AccountTransaction(
                        accountId,
                        TransactionType.DEPOSIT,
                        new BigDecimal("100.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("100.00"),
                        null
                )
        );

        Thread.sleep(5);

        transactionRepository.saveAndFlush(
                new AccountTransaction(
                        accountId,
                        TransactionType.WITHDRAWAL,
                        new BigDecimal("20.00"),
                        new BigDecimal("100.00"),
                        new BigDecimal("80.00"),
                        null
                )
        );

        List<AccountTransaction> history = transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(history.get(1).getType()).isEqualTo(TransactionType.DEPOSIT);
    }
}
