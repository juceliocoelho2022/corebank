package com.corebank.event.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record FinancialEventEnvelope(
        UUID eventId,
        DomainEventType eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        JsonNode payload) {
}
