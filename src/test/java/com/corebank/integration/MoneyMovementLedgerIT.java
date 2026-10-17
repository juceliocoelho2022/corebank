package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.transaction.application.TransactionService;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MoneyMovementLedgerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accountService;
    @Autowired TransactionService transactionService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalEntryRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_journal_insert ON journal_entries");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_journal_insert()");

        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_journal_insert ON journal_entries");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_journal_insert()");
    }

    @Test
    void deposit_shouldCommitOneJournalAndTwoLedgerEntries() {
        long journalsBefore = journalEntryRepository.count();
        long entriesBefore = ledgerEntryRepository.count();

        var transaction = transactionService.deposit(accountId, new BigDecimal("100.00"), "deposit");

        assertThat(transaction.getId()).isNotNull();
        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore + 2);
        Integer matchingReference = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reference_type = 'DEPOSIT' AND reference_id = ?",
                Integer.class,
                transaction.getId());
        assertThat(matchingReference).isEqualTo(1);
    }

    @Test
    void withdrawal_shouldCommitOneJournalAndTwoLedgerEntries() {
        transactionService.deposit(accountId, new BigDecimal("100.00"), "seed");
        long journalsBefore = journalEntryRepository.count();
        long entriesBefore = ledgerEntryRepository.count();

        var transaction = transactionService.withdraw(accountId, new BigDecimal("40.00"), "ATM");

        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore + 2);
        Integer matchingReference = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reference_type = 'WITHDRAWAL' AND reference_id = ?",
                Integer.class,
                transaction.getId());
        assertThat(matchingReference).isEqualTo(1);
    }

    @Test
    void forcedLedgerFailure_shouldRollbackBalanceAndTransactionHistory() {
        BigDecimal balanceBefore = accountRepository.findById(accountId).orElseThrow().getBalance();
        long transactionsBefore = transactionRepository.count();
        long journalsBefore = journalEntryRepository.count();
        long entriesBefore = ledgerEntryRepository.count();

        jdbcTemplate.execute("""
                CREATE FUNCTION fail_journal_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced journal insert failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail_journal_insert
                BEFORE INSERT ON journal_entries
                FOR EACH ROW
                EXECUTE FUNCTION fail_journal_insert()
                """);

        assertThatThrownBy(() -> transactionService.deposit(accountId, new BigDecimal("25.00"), "will rollback"))
                .isInstanceOf(DataAccessException.class);

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore);
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    void insufficientBalance_shouldCreateNoJournalOrLedgerRows() {
        long transactionsBefore = transactionRepository.count();
        long journalsBefore = journalEntryRepository.count();
        long entriesBefore = ledgerEntryRepository.count();

        assertThatThrownBy(() -> transactionService.withdraw(accountId, new BigDecimal("10.00"), "ATM"))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore);
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);
    }
}
