package com.corebank.idempotency.application;

import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.domain.IdempotencyRecord;
import com.corebank.idempotency.domain.IdempotencyStatus;
import com.corebank.idempotency.infrastructure.IdempotencyLockRepository;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.idempotency.infrastructure.IdempotencyReplayCache;
import com.corebank.idempotency.infrastructure.ReplayPointer;
import com.corebank.shared.exception.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final IdempotencyLockRepository lockRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyReplayCache replayCache;
    private final ApplicationEventPublisher events;
    private final IdempotencyMetrics metrics;

    public IdempotencyService(
            IdempotencyRecordRepository repository,
            IdempotencyLockRepository lockRepository,
            ObjectMapper objectMapper,
            IdempotencyReplayCache replayCache,
            ApplicationEventPublisher events,
            IdempotencyMetrics metrics) {
        this.repository = repository;
        this.lockRepository = lockRepository;
        this.objectMapper = objectMapper;
        this.replayCache = replayCache;
        this.events = events;
        this.metrics = metrics;
    }

    @Transactional
    public <T> IdempotencyExecution<T> execute(
            String key,
            IdempotencyOperation operation,
            String requestHash,
            Class<T> responseType,
            Supplier<IdempotencyCommandResult<T>> command) {

        Optional<ReplayPointer> cached = replayCache.find(key);
        if (cached.isPresent()) {
            ReplayPointer pointer = cached.get();
            if (pointer.operation() == operation && pointer.requestHash().equals(requestHash)) {
                Optional<IdempotencyRecord> pointed = repository.findById(pointer.recordId());
                if (pointed.isPresent() && isVerifiedPointer(pointed.get(), pointer, key, operation, requestHash)) {
                    return resolveExisting(pointed.get(), operation, requestHash, responseType);
                }
            }
            replayCache.evict(key);
        }

        Optional<IdempotencyRecord> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resolveExistingAndWarmCache(key, existing.get(), operation, requestHash, responseType);
        }

        if (!lockRepository.tryLock(key)) {
            metrics.conflict();
            throw new ConflictException("A request with this Idempotency-Key is already in progress");
        }

        existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resolveExistingAndWarmCache(key, existing.get(), operation, requestHash, responseType);
        }

        IdempotencyRecord record = repository.saveAndFlush(
                IdempotencyRecord.start(key, operation, requestHash));

        IdempotencyCommandResult<T> result = command.get();
        if (result == null || result.body() == null) {
            throw new IllegalStateException("Idempotent command must return a response body");
        }

        JsonNode responseBody = objectMapper.valueToTree(result.body());
        record.complete(result.resourceId(), result.httpStatus(), responseBody);
        repository.saveAndFlush(record);
        // Event publication occurs in the PostgreSQL transaction.
        // The listener writes to Redis only in AFTER_COMMIT.
        if (record.getId() != null) {
            events.publishEvent(new IdempotencyCompletedEvent(key, ReplayPointer.from(record)));
        }

        return new IdempotencyExecution<>(result.body(), result.httpStatus(), false);
    }

    private boolean isVerifiedPointer(IdempotencyRecord record, ReplayPointer pointer,
                                      String key, IdempotencyOperation operation, String hash) {
        return Objects.equals(record.getId(), pointer.recordId())
                && key.equals(record.getIdempotencyKey())
                && record.getStatus() == IdempotencyStatus.COMPLETED
                && record.getOperationType() == operation
                && record.getOperationType() == pointer.operation()
                && record.getRequestHash().equals(hash)
                && record.getRequestHash().equals(pointer.requestHash())
                && Objects.equals(record.getResourceId(), pointer.resourceId())
                && Objects.equals(record.getHttpStatus(), pointer.httpStatus())
                && record.getResponseBody() != null;
    }

    private <T> IdempotencyExecution<T> resolveExistingAndWarmCache(
            String key, IdempotencyRecord record, IdempotencyOperation operation,
            String requestHash, Class<T> responseType) {
        IdempotencyExecution<T> execution = resolveExisting(record, operation, requestHash, responseType);
        if (record.getId() != null) {
            // This row was committed by an earlier request, not by the current transaction.
            replayCache.putCompleted(key, ReplayPointer.from(record));
        }
        return execution;
    }

    private <T> IdempotencyExecution<T> resolveExisting(
            IdempotencyRecord record,
            IdempotencyOperation operation,
            String requestHash,
            Class<T> responseType) {

        if (record.getOperationType() != operation || !record.getRequestHash().equals(requestHash)) {
            metrics.conflict();
            throw new ConflictException("Idempotency-Key was already used with a different operation or request payload");
        }

        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            metrics.conflict();
            throw new ConflictException("A request with this Idempotency-Key is already in progress");
        }

        T body = objectMapper.convertValue(record.getResponseBody(), responseType);
        metrics.replayed();
        return new IdempotencyExecution<>(body, record.getHttpStatus(), true);
    }
}
