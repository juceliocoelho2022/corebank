package com.corebank.idempotency.infrastructure;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class IdempotencyMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpPreV5Database() {
        Flyway flyway = flyway(MigrationVersion.fromVersion("4"));
        flyway.clean();
        flyway.migrate();

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void migration_shouldCreateIdempotencyRecordsTable() {
        migrateLatest();

        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'idempotency_records'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void database_shouldRejectDuplicateIdempotencyKey() {
        migrateLatest();
        insertInProgress("payment-123", "DEPOSIT", "a".repeat(64));

        assertThatThrownBy(() -> insertInProgress("payment-123", "DEPOSIT", "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_shouldRestrictOperationStatusAndSha256Hash() {
        migrateLatest();

        assertThatThrownBy(() -> insertInProgress("invalid-op-1", "PAYMENT", "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO idempotency_records
                    (id, idempotency_key, operation_type, request_hash, status, created_at)
                VALUES (?, ?, 'DEPOSIT', ?, 'DONE', ?)
                """, UUID.randomUUID(), "invalid-status-1", "a".repeat(64), Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInProgress("invalid-hash-1", "DEPOSIT", "ABCDEF" + "a".repeat(58)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInProgress("invalid-hash-2", "DEPOSIT", "a".repeat(63)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completedRecord_shouldRequireReplayMetadata() {
        migrateLatest();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO idempotency_records
                    (id, idempotency_key, operation_type, request_hash, status, created_at)
                VALUES (?, ?, 'TRANSFER', ?, 'COMPLETED', ?)
                """, UUID.randomUUID(), "transfer-123", "c".repeat(64), Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_shouldAcceptValidCompletedRecord() {
        migrateLatest();
        UUID resourceId = UUID.randomUUID();
        Instant now = Instant.now();

        int inserted = jdbc.update("""
                INSERT INTO idempotency_records
                    (id, idempotency_key, operation_type, request_hash, status, resource_id,
                     response_body, http_status, created_at, completed_at)
                VALUES (?, ?, 'WITHDRAWAL', ?, 'COMPLETED', ?, CAST(? AS jsonb), 201, ?, ?)
                """, UUID.randomUUID(), "withdrawal-123", "d".repeat(64), resourceId,
                "{\"transactionId\":\"" + resourceId + "\"}", Timestamp.from(now), Timestamp.from(now));

        assertThat(inserted).isEqualTo(1);
    }

    private void insertInProgress(String key, String operation, String hash) {
        jdbc.update("""
                INSERT INTO idempotency_records
                    (id, idempotency_key, operation_type, request_hash, status, created_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?)
                """, UUID.randomUUID(), key, operation, hash, Timestamp.from(Instant.now()));
    }

    private void migrateLatest() {
        flyway(MigrationVersion.LATEST).migrate();
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false)
                .target(target)
                .load();
    }
}
