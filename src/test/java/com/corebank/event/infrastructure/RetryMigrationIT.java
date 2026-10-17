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
class RetryMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpAtV6() {
        Flyway flyway = flyway(MigrationVersion.fromVersion("6"));
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void migration_shouldMakeLegacyPendingRowsImmediatelyEligible() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T18:30:00Z");
        insertV6Row(id, "PENDING", createdAt, null);

        migrateLatest();

        var state = jdbc.queryForMap("""
                SELECT attempt_count, next_attempt_at, last_error_type, last_error_message
                FROM outbox_events
                WHERE id = ?
                """, id);

        assertThat(((Number) state.get("attempt_count")).intValue()).isZero();
        assertThat(((Timestamp) state.get("next_attempt_at")).toInstant()).isEqualTo(createdAt);
        assertThat(state.get("last_error_type")).isNull();
        assertThat(state.get("last_error_message")).isNull();
    }

    @Test
    void migration_shouldPreservePublishedRowsAndAllowDeadLetteredStatus() {
        UUID publishedId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T18:30:00Z");
        Instant publishedAt = Instant.parse("2026-10-17T18:31:00Z");
        insertV6Row(publishedId, "PUBLISHED", createdAt, publishedAt);

        migrateLatest();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?",
                String.class,
                publishedId)).isEqualTo("PUBLISHED");

        UUID deadLetteredId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_type, event_version, aggregate_type, aggregate_id, correlation_id,
                     message_key, payload, status, created_at, published_at, attempt_count,
                     next_attempt_at, last_error_type, last_error_message)
                VALUES (?, 'DEPOSIT_COMPLETED', 1, 'ACCOUNT', ?, ?, ?, CAST(? AS jsonb),
                        'DEAD_LETTERED', ?, NULL, 5, ?, 'TimeoutException', 'Kafka publication failed')
                """,
                deadLetteredId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "{\"amount\":10.00,\"currency\":\"BRL\"}",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void migration_shouldRejectNegativeAttemptCountAndCreateEligibilityIndex() {
        migrateLatest();

        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_type, event_version, aggregate_type, aggregate_id, correlation_id,
                     message_key, payload, status, created_at, published_at, attempt_count,
                     next_attempt_at)
                VALUES (?, 'DEPOSIT_COMPLETED', 1, 'ACCOUNT', ?, ?, ?, CAST(? AS jsonb),
                        'PENDING', ?, NULL, -1, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "{\"amount\":10.00,\"currency\":\"BRL\"}",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer indexCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'outbox_events'
                  AND indexname = 'idx_outbox_pending_next_attempt'
                  AND indexdef ILIKE '%next_attempt_at%'
                  AND indexdef ILIKE '%created_at%'
                  AND indexdef ILIKE '%PENDING%'
                """, Integer.class);

        assertThat(indexCount).isEqualTo(1);
    }

    private void insertV6Row(UUID id, String status, Instant createdAt, Instant publishedAt) {
        UUID aggregateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_type, event_version, aggregate_type, aggregate_id, correlation_id,
                     message_key, payload, status, created_at, published_at)
                VALUES (?, 'DEPOSIT_COMPLETED', 1, 'ACCOUNT', ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """,
                id,
                aggregateId,
                UUID.randomUUID(),
                aggregateId.toString(),
                "{\"amount\":10.00,\"currency\":\"BRL\"}",
                status,
                Timestamp.from(createdAt),
                publishedAt == null ? null : Timestamp.from(publishedAt));
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
