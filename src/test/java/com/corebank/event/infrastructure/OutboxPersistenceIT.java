package com.corebank.event.infrastructure;

import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    OutboxEventRepository repository;

    @Test
    void repository_shouldPersistAndReloadPendingEventContract() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        OutboxEvent persisted = repository.saveAndFlush(OutboxEvent.pending(
                DomainEventType.DEPOSIT_COMPLETED,
                1,
                "ACCOUNT",
                accountId,
                transactionId,
                accountId.toString(),
                JsonNodeFactory.instance.objectNode()
                        .put("accountId", accountId.toString())
                        .put("transactionId", transactionId.toString())
                        .put("amount", "100.00")
                        .put("currency", "BRL")));

        OutboxEvent reloaded = repository.findById(persisted.getId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(persisted.getId());
        assertThat(reloaded.getEventType()).isEqualTo(DomainEventType.DEPOSIT_COMPLETED);
        assertThat(reloaded.getEventVersion()).isEqualTo(1);
        assertThat(reloaded.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(reloaded.getAggregateId()).isEqualTo(accountId);
        assertThat(reloaded.getCorrelationId()).isEqualTo(transactionId);
        assertThat(reloaded.getMessageKey()).isEqualTo(accountId.toString());
        assertThat(reloaded.getPayload().path("currency").asText()).isEqualTo("BRL");
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getPublishedAt()).isNull();
    }
}
