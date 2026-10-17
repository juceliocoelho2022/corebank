package com.corebank.event.infrastructure;

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
class OutboxMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpPreV6Database() {
        Flyway flyway = flyway(MigrationVersion.fromVersion("5"));
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void migration_shouldCreateOutboxTableAndCurrentPendingIndex() {
        migrateLatest();

        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'outbox_events'
                """, Integer.class);
        Integer indexCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'outbox_events'
                  AND indexname = 'idx_outbox_pending_next_attempt'
                  AND indexdef ILIKE '%next_attempt_at%'
                  AND indexdef ILIKE '%PENDING%'
                """, Integer.class);

        assertThat(tableCount).isEqualTo(1);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void database_shouldEnforceStatusVersionAndPublicationMetadata() {
        migrateLatest();
        Instant now = Instant.now();

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "DEPOSIT_COMPLETED", 0, "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "DEPOSIT_COMPLETED", 1, "FAILED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "DEPOSIT_COMPLETED", 1, "PENDING", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "DEPOSIT_COMPLETED", 1, "PUBLISHED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "DEPOSIT_COMPLETED", 1, "DEAD_LETTERED", now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void database_shouldAcceptValidPendingPublishedAndDeadLetteredRows() {
        migrateLatest();
        Instant now = Instant.now();

        assertThat(insert(UUID.randomUUID(), "DEPOSIT_COMPLETED", 1, "PENDING", null)).isEqualTo(1);
        assertThat(insert(UUID.randomUUID(), "TRANSFER_COMPLETED", 1, "PUBLISHED", now)).isEqualTo(1);
        assertThat(insert(UUID.randomUUID(), "WITHDRAWAL_COMPLETED", 1, "DEAD_LETTERED", null)).isEqualTo(1);
    }

    private int insert(UUID id, String eventType, int version, String status, Instant publishedAt) {
        UUID aggregateId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        return jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_type, event_version, aggregate_type, aggregate_id, correlation_id,
                     message_key, payload, status, created_at, published_at, attempt_count, next_attempt_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, 0, ?)
                """,
                id,
                eventType,
                version,
                aggregateId,
                UUID.randomUUID(),
                aggregateId.toString(),
                "{\"amount\":100.00,\"currency\":\"BRL\"}",
                status,
                Timestamp.from(createdAt),
                publishedAt == null ? null : Timestamp.from(publishedAt),
                Timestamp.from(createdAt));
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
