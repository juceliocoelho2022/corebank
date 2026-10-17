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
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

    @Id
    private UUID id;

    @Column(name = "original_event_id", nullable = false, unique = true)
    private UUID originalEventId;

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

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error_type", nullable = false, length = 128)
    private String lastErrorType;

    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeadLetterStatus status;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "last_publish_error_type", length = 128)
    private String lastPublishErrorType;

    @Column(name = "last_publish_error_message", length = 500)
    private String lastPublishErrorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected DeadLetterEvent() {
    }

    private DeadLetterEvent(OutboxEvent event, String errorType, String errorMessage) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.getStatus() != OutboxStatus.DEAD_LETTERED) {
            throw new IllegalArgumentException("Outbox event must be DEAD_LETTERED");
        }
        this.id = UUID.randomUUID();
        this.originalEventId = event.getId();
        this.eventType = event.getEventType();
        this.eventVersion = event.getEventVersion();
        this.aggregateType = event.getAggregateType();
        this.aggregateId = event.getAggregateId();
        this.correlationId = event.getCorrelationId();
        this.messageKey = event.getMessageKey();
        this.payload = event.getPayload().deepCopy();
        this.attemptCount = event.getAttemptCount();
        this.lastErrorType = boundedRequiredText(errorType, "errorType", 128);
        this.lastErrorMessage = boundedNullableText(errorMessage, 500);
        Instant now = Instant.now();
        this.failedAt = now;
        this.status = DeadLetterStatus.PENDING;
        this.nextAttemptAt = now;
        this.publishAttempts = 0;
        this.createdAt = now;
    }

    public static DeadLetterEvent from(OutboxEvent event, String errorType, String errorMessage) {
        return new DeadLetterEvent(event, errorType, errorMessage);
    }

    public void recordPublishFailure(String errorType, String errorMessage, Instant nextAttemptAt) {
        requirePending();
        this.publishAttempts++;
        this.lastPublishErrorType = boundedRequiredText(errorType, "errorType", 128);
        this.lastPublishErrorMessage = boundedNullableText(errorMessage, 500);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    }

    public void markPublished(Instant publishedAt) {
        requirePending();
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.status = DeadLetterStatus.PUBLISHED;
        this.lastPublishErrorType = null;
        this.lastPublishErrorMessage = null;
    }

    public DeadLetterEnvelope toEnvelope() {
        return new DeadLetterEnvelope(
                id,
                originalEventId,
                eventType,
                eventVersion,
                aggregateType,
                aggregateId,
                correlationId,
                failedAt,
                attemptCount,
                lastErrorType,
                lastErrorMessage,
                payload.deepCopy());
    }

    private void requirePending() {
        if (status != DeadLetterStatus.PENDING) {
            throw new IllegalStateException("Dead-letter event is terminal: " + status);
        }
    }

    private static String boundedRequiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
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
    public UUID getOriginalEventId() { return originalEventId; }
    public DomainEventType getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public UUID getCorrelationId() { return correlationId; }
    public String getMessageKey() { return messageKey; }
    public JsonNode getPayload() { return payload.deepCopy(); }
    public int getAttemptCount() { return attemptCount; }
    public String getLastErrorType() { return lastErrorType; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public Instant getFailedAt() { return failedAt; }
    public DeadLetterStatus getStatus() { return status; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public int getPublishAttempts() { return publishAttempts; }
    public String getLastPublishErrorType() { return lastPublishErrorType; }
    public String getLastPublishErrorMessage() { return lastPublishErrorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
