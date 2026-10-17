package com.corebank.ledger.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LedgerMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private UUID fundedAccountId;
    private UUID zeroBalanceAccountId;

    @BeforeEach
    void setUpPreV4Database() {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway(null).migrate();

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        UUID customerId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO customers (id, name, document, email, created_at, updated_at)
                VALUES (?, 'Ana Silva', ?, ?, ?, ?)
                """, customerId, UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com",
                Timestamp.from(now), Timestamp.from(now));

        fundedAccountId = UUID.randomUUID();
        zeroBalanceAccountId = UUID.randomUUID();
        insertAccount(fundedAccountId, customerId, "10008881", new BigDecimal("125.50"), now);
        insertAccount(zeroBalanceAccountId, customerId, "10008882", new BigDecimal("0.00"), now);
    }

    @Test
    void migration_shouldCreateCashClearingAndCustomerLiabilityAccounts() {
        migrateLatest();

        Integer clearingCount = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE code = 'TECH:CASH_CLEARING' AND type = 'CASH_CLEARING' AND account_id IS NULL",
                Integer.class);
        Integer fundedLiabilityCount = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE code = ? AND type = 'CUSTOMER_LIABILITY' AND account_id = ?",
                Integer.class, "CUSTOMER:" + fundedAccountId, fundedAccountId);
        Integer zeroLiabilityCount = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE code = ? AND type = 'CUSTOMER_LIABILITY' AND account_id = ?",
                Integer.class, "CUSTOMER:" + zeroBalanceAccountId, zeroBalanceAccountId);

        assertThat(clearingCount).isEqualTo(1);
        assertThat(fundedLiabilityCount).isEqualTo(1);
        assertThat(zeroLiabilityCount).isEqualTo(1);
    }

    @Test
    void migration_shouldCreateOpeningBalanceJournalForExistingNonZeroAccount() {
        migrateLatest();

        UUID journalId = jdbc.queryForObject(
                "SELECT id FROM journal_entries WHERE reference_type = 'OPENING_BALANCE' AND reference_id = ?",
                UUID.class, fundedAccountId);
        assertThat(journalId).isNotNull();

        BigDecimal debit = jdbc.queryForObject(
                "SELECT COALESCE(sum(amount), 0) FROM ledger_entries WHERE journal_entry_id = ? AND side = 'DEBIT'",
                BigDecimal.class, journalId);
        BigDecimal credit = jdbc.queryForObject(
                "SELECT COALESCE(sum(amount), 0) FROM ledger_entries WHERE journal_entry_id = ? AND side = 'CREDIT'",
                BigDecimal.class, journalId);

        assertThat(debit).isEqualByComparingTo("125.50");
        assertThat(credit).isEqualByComparingTo("125.50");
    }

    @Test
    void migration_shouldNotCreateOpeningBalanceJournalForZeroBalanceAccount() {
        migrateLatest();

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM journal_entries WHERE reference_type = 'OPENING_BALANCE' AND reference_id = ?",
                Integer.class, zeroBalanceAccountId);

        assertThat(count).isZero();
    }

    @Test
    void database_shouldRejectDuplicateCustomerLedgerAccount() {
        migrateLatest();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_accounts (id, code, type, account_id, currency, created_at)
                VALUES (?, ?, 'CUSTOMER_LIABILITY', ?, 'BRL', ?)
                """, UUID.randomUUID(), "CUSTOMER:DUPLICATE:" + fundedAccountId, fundedAccountId,
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_shouldRejectUpdateOrDeleteOnJournalAndLedgerEntries() {
        migrateLatest();

        UUID journalId = jdbc.queryForObject(
                "SELECT id FROM journal_entries WHERE reference_type = 'OPENING_BALANCE' AND reference_id = ?",
                UUID.class, fundedAccountId);
        UUID ledgerEntryId = jdbc.queryForObject(
                "SELECT id FROM ledger_entries WHERE journal_entry_id = ? ORDER BY id LIMIT 1",
                UUID.class, journalId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE journal_entries SET description = 'tampered' WHERE id = ?", journalId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM journal_entries WHERE id = ?", journalId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount = 1.00 WHERE id = ?", ledgerEntryId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE id = ?", ledgerEntryId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrateLatest() {
        flyway(MigrationVersion.LATEST).migrate();
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false);
        if (target == null) {
            configuration.target(MigrationVersion.fromVersion("3"));
        } else {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertAccount(UUID accountId, UUID customerId, String number, BigDecimal balance, Instant now) {
        jdbc.update("""
                INSERT INTO accounts (id, customer_id, branch, number, status, balance, currency, version, created_at, updated_at)
                VALUES (?, ?, '0001', ?, 'ACTIVE', ?, 'BRL', 0, ?, ?)
                """, accountId, customerId, number, balance, Timestamp.from(now), Timestamp.from(now));
    }
}
