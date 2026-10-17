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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferTransactionPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID accountId;
    private UUID counterpartyId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        accountId = accountRepository.saveAndFlush(new Account(customer.getId(), "10005551")).getId();
        counterpartyId = accountRepository.saveAndFlush(new Account(customer.getId(), "10005552")).getId();
    }

    @Test
    void flywayV3_shouldPersistTransferMetadataAndKeepLegacyMetadataNull() {
        UUID transferId = UUID.randomUUID();

        AccountTransaction out = transactionRepository.saveAndFlush(AccountTransaction.transfer(
                accountId, TransactionType.TRANSFER_OUT, new BigDecimal("25.00"),
                new BigDecimal("100.00"), new BigDecimal("75.00"), "transfer", transferId, counterpartyId));
        AccountTransaction deposit = transactionRepository.saveAndFlush(new AccountTransaction(
                accountId, TransactionType.DEPOSIT, new BigDecimal("10.00"),
                new BigDecimal("75.00"), new BigDecimal("85.00"), null));

        assertThat(out.getTransferId()).isEqualTo(transferId);
        assertThat(out.getCounterpartyAccountId()).isEqualTo(counterpartyId);
        assertThat(deposit.getTransferId()).isNull();
        assertThat(deposit.getCounterpartyAccountId()).isNull();
    }

    @Test
    void database_shouldRejectTransferWithoutCorrelationMetadata() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO account_transactions (id, account_id, type, amount, balance_before, balance_after, transfer_id, counterparty_account_id, created_at) VALUES (?, ?, 'TRANSFER_OUT', 10.00, 20.00, 10.00, NULL, NULL, ?)",
                UUID.randomUUID(), accountId, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_shouldRejectLegacyMovementWithTransferMetadata() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO account_transactions (id, account_id, type, amount, balance_before, balance_after, transfer_id, counterparty_account_id, created_at) VALUES (?, ?, 'DEPOSIT', 10.00, 0.00, 10.00, ?, ?, ?)",
                UUID.randomUUID(), accountId, UUID.randomUUID(), counterpartyId, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
