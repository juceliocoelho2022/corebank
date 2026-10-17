package com.corebank.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private IdempotencyOperation operationType;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(String idempotencyKey, IdempotencyOperation operationType, String requestHash) {
        this.idempotencyKey = validateKey(idempotencyKey);
        this.operationType = Objects.requireNonNull(operationType, "operationType must not be null");
        this.requestHash = validateHash(requestHash);
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    public static IdempotencyRecord start(String key, IdempotencyOperation operation, String requestHash) {
        return new IdempotencyRecord(key, operation, requestHash);
    }

    public void complete(UUID resourceId, int httpStatus, String responseBody) {
        if (status == IdempotencyStatus.COMPLETED) {
            throw new IllegalStateException("Idempotency record is already completed");
        }
        if (responseBody == null) {
            throw new IllegalArgumentException("responseBody must not be null");
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be a valid HTTP status code");
        }

        this.resourceId = resourceId;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.completedAt = Instant.now();
        this.status = IdempotencyStatus.COMPLETED;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static String validateKey(String key) {
        if (key == null || key.length() < 8 || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must contain between 8 and 128 characters");
        }
        return key;
    }

    private static String validateHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lower-case SHA-256 hex digest");
        }
        return hash;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public IdempotencyOperation getOperationType() { return operationType; }
    public String getRequestHash() { return requestHash; }
    public IdempotencyStatus getStatus() { return status; }
    public UUID getResourceId() { return resourceId; }
    public String getResponseBody() { return responseBody; }
    public Integer getHttpStatus() { return httpStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
