package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import com.corebank.transfer.application.TransferResult;
import com.corebank.transfer.application.TransferService;
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
class TransferLedgerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired TransferService transferService;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalEntryRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_transfer_journal ON journal_entries");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_transfer_journal_insert()");

        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        Account source = accountService.create(customer.getId());
        Account destination = accountService.create(customer.getId());
        source.deposit(new BigDecimal("100.00"));
        accountRepository.saveAndFlush(source);
        sourceId = source.getId();
        destinationId = destination.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_transfer_journal ON journal_entries");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_transfer_journal_insert()");
    }

    @Test
    void successfulTransfer_shouldCreateOneBalancedJournalWithExistingTransferId() {
        long journalsBefore = journalEntryRepository.count();
        long entriesBefore = ledgerEntryRepository.count();

        TransferResult result = transferService.transfer(
                sourceId, destinationId, new BigDecimal("30.00"), "Rent");

        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore + 2);

        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reference_type = 'TRANSFER' AND reference_id = ?",
                Integer.class,
                result.transferId());
        assertThat(matches).isEqualTo(1);

        var lines = jdbcTemplate.queryForList("""
                SELECT le.side, le.amount, la.account_id
                FROM ledger_entries le
                JOIN journal_entries je ON je.id = le.journal_entry_id
                JOIN ledger_accounts la ON la.id = le.ledger_account_id
                WHERE je.reference_id = ?
                ORDER BY le.side
                """, result.transferId());
        assertThat(lines).hasSize(2);
        assertThat(lines).anySatisfy(row -> {
            assertThat(row.get("side")).isEqualTo("DEBIT");
            assertThat(row.get("account_id")).isEqualTo(sourceId);
            assertThat(row.get("amount").toString()).isEqualTo("30.00");
        });
        assertThat(lines).anySatisfy(row -> {
            assertThat(row.get("side")).isEqualTo("CREDIT");
            assertThat(row.get("account_id")).isEqualTo(destinationId);
            assertThat(row.get("amount").toString()).isEqualTo("30.00");
        });
    }

    @Test
    void ledgerFailure_shouldRollbackBothBalancesAndTransferHistory() {
        BigDecimal sourceBefore = accountRepository.findById(sourceId).orElseThrow().getBalance();
        BigDecimal destinationBefore = accountRepository.findById(destinationId).orElseThrow().getBalance();
        long txBefore = transactionRepository.count();
        long journalsBefore = journalEntryRepository.count();
        long entriesBefore = ledgerEntryRepository.count();

        jdbcTemplate.execute("""
                CREATE FUNCTION fail_transfer_journal_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.reference_type = 'TRANSFER' THEN
                        RAISE EXCEPTION 'forced transfer journal failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail_transfer_journal
                BEFORE INSERT ON journal_entries
                FOR EACH ROW
                EXECUTE FUNCTION fail_transfer_journal_insert()
                """);

        assertThatThrownBy(() -> transferService.transfer(
                sourceId, destinationId, new BigDecimal("30.00"), "Rent"))
                .isInstanceOf(DataAccessException.class);

        assertThat(accountRepository.findById(sourceId).orElseThrow().getBalance())
                .isEqualByComparingTo(sourceBefore);
        assertThat(accountRepository.findById(destinationId).orElseThrow().getBalance())
                .isEqualByComparingTo(destinationBefore);
        assertThat(transactionRepository.count()).isEqualTo(txBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore);
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);
    }
}
