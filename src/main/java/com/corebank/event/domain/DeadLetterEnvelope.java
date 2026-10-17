package com.corebank.event.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterEnvelope(
        UUID deadLetterId,
        UUID originalEventId,
        DomainEventType eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        Instant failedAt,
        int attemptCount,
        String errorType,
        String errorMessage,
        JsonNode payload) {
}
