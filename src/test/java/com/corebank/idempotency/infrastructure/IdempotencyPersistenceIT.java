package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.domain.IdempotencyRecord;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    IdempotencyRecordRepository repository;

    @Test
    void repository_shouldPersistAndFindByOpaqueIdempotencyKey() {
        IdempotencyRecord persisted = repository.saveAndFlush(IdempotencyRecord.start(
                "Payment-AbC-123",
                IdempotencyOperation.DEPOSIT,
                "a".repeat(64)));

        Optional<IdempotencyRecord> found = repository.findByIdempotencyKey("Payment-AbC-123");
        Optional<IdempotencyRecord> differentCase = repository.findByIdempotencyKey("payment-abc-123");

        assertThat(persisted.getId()).isNotNull();
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId()).isEqualTo(persisted.getId());
        assertThat(differentCase).isEmpty();
    }

    @Test
    void repository_shouldPersistCompletedReplayMetadata() {
        IdempotencyRecord record = IdempotencyRecord.start(
                "withdrawal-123",
                IdempotencyOperation.WITHDRAWAL,
                "b".repeat(64));
        record.complete(null, 201, JsonNodeFactory.instance.objectNode().put("type", "WITHDRAWAL"));

        IdempotencyRecord persisted = repository.saveAndFlush(record);
        IdempotencyRecord reloaded = repository.findByIdempotencyKey("withdrawal-123").orElseThrow();

        assertThat(reloaded.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(reloaded.getHttpStatus()).isEqualTo(201);
        assertThat(reloaded.getResponseBody().path("type").asText()).isEqualTo("WITHDRAWAL");
        assertThat(reloaded.getCompletedAt()).isNotNull();
        assertThat(reloaded.getId()).isEqualTo(persisted.getId());
    }
}
