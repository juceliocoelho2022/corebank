package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.reconciliation.application.ReconciliationResult;
import com.corebank.reconciliation.application.ReconciliationService;
import com.corebank.reconciliation.application.ReconciliationStatus;
import com.corebank.transaction.application.TransactionService;
import com.corebank.transfer.application.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired ReconciliationService reconciliationService;
    @Autowired AccountService accountService;
    @Autowired TransactionService transactionService;
    @Autowired TransferService transferService;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void depositWithdrawalAndTransferSequence_shouldRemainReconciled() {
        UUID customerId = newCustomer();
        Account source = accountService.create(customerId);
        Account destination = accountService.create(customerId);

        transactionService.deposit(source.getId(), new BigDecimal("200.00"), "funding");
        transactionService.withdraw(source.getId(), new BigDecimal("50.00"), "cash");
        transferService.transfer(source.getId(), destination.getId(), new BigDecimal("40.00"), "move");

        ReconciliationResult sourceResult = reconciliationService.reconcile(source.getId());
        ReconciliationResult destinationResult = reconciliationService.reconcile(destination.getId());

        assertThat(sourceResult.operationalBalance()).isEqualByComparingTo("110.00");
        assertThat(sourceResult.ledgerBalance()).isEqualByComparingTo("110.00");
        assertThat(sourceResult.difference()).isEqualByComparingTo("0.00");
        assertThat(sourceResult.status()).isEqualTo(ReconciliationStatus.RECONCILED);

        assertThat(destinationResult.operationalBalance()).isEqualByComparingTo("40.00");
        assertThat(destinationResult.ledgerBalance()).isEqualByComparingTo("40.00");
        assertThat(destinationResult.difference()).isEqualByComparingTo("0.00");
        assertThat(destinationResult.status()).isEqualTo(ReconciliationStatus.RECONCILED);
    }

    @Test
    void migrationShapedOpeningBalance_shouldBeImmediatelyReconciled() {
        UUID customerId = newCustomer();
        UUID accountId = UUID.randomUUID();
        UUID customerLedgerId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        UUID clearingId = jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_accounts WHERE code = 'TECH:CASH_CLEARING'",
                UUID.class);
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO accounts
                    (id, customer_id, branch, number, status, balance, currency, version, created_at, updated_at)
                VALUES (?, ?, '0001', ?, 'ACTIVE', 75.00, 'BRL', 0, ?, ?)
                """, accountId, customerId, uniqueAccountNumber(), now, now);
        jdbcTemplate.update("""
                INSERT INTO ledger_accounts (id, code, type, account_id, currency, created_at)
                VALUES (?, ?, 'CUSTOMER_LIABILITY', ?, 'BRL', ?)
                """, customerLedgerId, "CUSTOMER:" + accountId, accountId, now);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (id, reference_type, reference_id, description, created_at)
                VALUES (?, 'OPENING_BALANCE', ?, 'Opening balance migration', ?)
                """, journalId, accountId, now);
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, journal_entry_id, ledger_account_id, side, amount, created_at)
                VALUES (?, ?, ?, 'DEBIT', 75.00, ?),
                       (?, ?, ?, 'CREDIT', 75.00, ?)
                """,
                UUID.randomUUID(), journalId, clearingId, now,
                UUID.randomUUID(), journalId, customerLedgerId, now);

        ReconciliationResult result = reconciliationService.reconcile(accountId);

        assertThat(result.operationalBalance()).isEqualByComparingTo("75.00");
        assertThat(result.ledgerBalance()).isEqualByComparingTo("75.00");
        assertThat(result.difference()).isEqualByComparingTo("0.00");
        assertThat(result.status()).isEqualTo(ReconciliationStatus.RECONCILED);
    }

    @Test
    void operationalTampering_shouldReportMismatchWithoutAutoCorrection() {
        UUID customerId = newCustomer();
        Account account = accountService.create(customerId);
        transactionService.deposit(account.getId(), new BigDecimal("100.00"), "funding");

        jdbcTemplate.update("UPDATE accounts SET balance = 125.00 WHERE id = ?", account.getId());

        ReconciliationResult result = reconciliationService.reconcile(account.getId());

        assertThat(result.operationalBalance()).isEqualByComparingTo("125.00");
        assertThat(result.ledgerBalance()).isEqualByComparingTo("100.00");
        assertThat(result.difference()).isEqualByComparingTo("25.00");
        assertThat(result.status()).isEqualTo(ReconciliationStatus.MISMATCH);

        BigDecimal persisted = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account.getId());
        assertThat(persisted).isEqualByComparingTo("125.00");
    }

    private UUID newCustomer() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        return customer.getId();
    }

    private String uniqueAccountNumber() {
        long value = Math.floorMod(System.nanoTime(), 90_000_000L) + 10_000_000L;
        return Long.toString(value).substring(0, 8);
    }
}
