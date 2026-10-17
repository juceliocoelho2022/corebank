package com.corebank.idempotency.application;

import java.util.UUID;

public record IdempotencyCommandResult<T>(UUID resourceId, int httpStatus, T body) {
}
