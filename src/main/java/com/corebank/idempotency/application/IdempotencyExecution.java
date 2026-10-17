package com.corebank.idempotency.application;

public record IdempotencyExecution<T>(T body, int httpStatus, boolean replayed) {
}
