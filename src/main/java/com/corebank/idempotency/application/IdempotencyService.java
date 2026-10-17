package com.corebank.idempotency.application;

import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.domain.IdempotencyRecord;
import com.corebank.idempotency.domain.IdempotencyStatus;
import com.corebank.idempotency.infrastructure.IdempotencyLockRepository;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.shared.exception.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final IdempotencyLockRepository lockRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
            IdempotencyRecordRepository repository,
            IdempotencyLockRepository lockRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.lockRepository = lockRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> IdempotencyExecution<T> execute(
            String key,
            IdempotencyOperation operation,
            String requestHash,
            Class<T> responseType,
            Supplier<IdempotencyCommandResult<T>> command) {

        Optional<IdempotencyRecord> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), operation, requestHash, responseType);
        }

        if (!lockRepository.tryLock(key)) {
            throw new ConflictException("A request with this Idempotency-Key is already in progress");
        }

        existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), operation, requestHash, responseType);
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

        return new IdempotencyExecution<>(result.body(), result.httpStatus(), false);
    }

    private <T> IdempotencyExecution<T> resolveExisting(
            IdempotencyRecord record,
            IdempotencyOperation operation,
            String requestHash,
            Class<T> responseType) {

        if (record.getOperationType() != operation || !record.getRequestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency-Key was already used with a different operation or request payload");
        }

        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new ConflictException("A request with this Idempotency-Key is already in progress");
        }

        T body = objectMapper.convertValue(record.getResponseBody(), responseType);
        return new IdempotencyExecution<>(body, record.getHttpStatus(), true);
    }
}
