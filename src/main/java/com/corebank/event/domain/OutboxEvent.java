package com.corebank.event.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private DomainEventType eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(
            DomainEventType eventType,
            int eventVersion,
            String aggregateType,
            UUID aggregateId,
            UUID correlationId,
            String messageKey,
            JsonNode payload) {
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1");
        }
        this.id = UUID.randomUUID();
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.eventVersion = eventVersion;
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.messageKey = requireText(messageKey, "messageKey");
        if (messageKey.length() > 128) {
            throw new IllegalArgumentException("messageKey must have at most 128 characters");
        }
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("payload must not be null");
        }
        this.payload = payload.deepCopy();
        this.status = OutboxStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static OutboxEvent pending(
            DomainEventType eventType,
            int eventVersion,
            String aggregateType,
            UUID aggregateId,
            UUID correlationId,
            String messageKey,
            JsonNode payload) {
        return new OutboxEvent(
                eventType,
                eventVersion,
                aggregateType,
                aggregateId,
                correlationId,
                messageKey,
                payload);
    }

    public void markPublished(Instant publishedAt) {
        if (status == OutboxStatus.PUBLISHED) {
            throw new IllegalStateException("Outbox event is already published");
        }
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.status = OutboxStatus.PUBLISHED;
    }

    public FinancialEventEnvelope toEnvelope() {
        return new FinancialEventEnvelope(
                id,
                eventType,
                eventVersion,
                createdAt,
                aggregateType,
                aggregateId,
                correlationId,
                payload.deepCopy());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID getId() { return id; }
    public DomainEventType getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public UUID getCorrelationId() { return correlationId; }
    public String getMessageKey() { return messageKey; }
    public JsonNode getPayload() { return payload.deepCopy(); }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
