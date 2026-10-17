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

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_type", length = 128)
    private String lastErrorType;

    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

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
        Instant now = Instant.now();
        this.createdAt = now;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
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

    public void recordRetriableFailure(String errorType, String errorMessage, Instant nextAttemptAt) {
        requirePending();
        this.attemptCount++;
        this.lastErrorType = boundedRequiredText(errorType, "errorType", 128);
        this.lastErrorMessage = boundedNullableText(errorMessage, 500);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    }

    public void markPublished(Instant publishedAt) {
        requirePending();
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.status = OutboxStatus.PUBLISHED;
        this.lastErrorType = null;
        this.lastErrorMessage = null;
    }

    public void markDeadLettered(String errorType, String errorMessage) {
        requirePending();
        this.attemptCount++;
        this.lastErrorType = boundedRequiredText(errorType, "errorType", 128);
        this.lastErrorMessage = boundedNullableText(errorMessage, 500);
        this.status = OutboxStatus.DEAD_LETTERED;
        this.publishedAt = null;
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

    private void requirePending() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException("Outbox event is terminal: " + status);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String boundedRequiredText(String value, String field, int maxLength) {
        String text = requireText(value, field);
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String boundedNullableText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
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
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastErrorType() { return lastErrorType; }
    public String getLastErrorMessage() { return lastErrorMessage; }
}
