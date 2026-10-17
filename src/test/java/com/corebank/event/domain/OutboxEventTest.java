package com.corebank.event.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    @Test
    void pendingEvent_shouldStartPendingAndExposeIdAsEventId() {
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("amount", "100.00");

        OutboxEvent event = OutboxEvent.pending(
                DomainEventType.DEPOSIT_COMPLETED,
                1,
                "ACCOUNT",
                aggregateId,
                correlationId,
                aggregateId.toString(),
                payload);

        assertThat(event.getId()).isNotNull();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.toEnvelope().eventId()).isEqualTo(event.getId());
        assertThat(event.toEnvelope().occurredAt()).isEqualTo(event.getCreatedAt());
    }

    @Test
    void markPublished_shouldSetStatusAndExactTimestamp() {
        OutboxEvent event = pendingEvent();
        Instant publishedAt = Instant.parse("2026-10-17T15:45:00Z");

        event.markPublished(publishedAt);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void pendingEvent_shouldRejectInvalidVersion() {
        assertThatThrownBy(() -> OutboxEvent.pending(
                DomainEventType.DEPOSIT_COMPLETED,
                0,
                "ACCOUNT",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
    }

    @Test
    void publishedEvent_shouldNotBePublishedTwice() {
        OutboxEvent event = pendingEvent();
        event.markPublished(Instant.now());

        assertThatThrownBy(() -> event.markPublished(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static OutboxEvent pendingEvent() {
        UUID accountId = UUID.randomUUID();
        return OutboxEvent.pending(
                DomainEventType.DEPOSIT_COMPLETED,
                1,
                "ACCOUNT",
                accountId,
                UUID.randomUUID(),
                accountId.toString(),
                JsonNodeFactory.instance.objectNode().put("currency", "BRL"));
    }
}
