package com.corebank.idempotency.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void start_shouldCreateInProgressRecordWithOpaqueKey() {
        IdempotencyRecord record = IdempotencyRecord.start(
                "Payment-AbC-123", IdempotencyOperation.DEPOSIT, HASH);

        assertThat(record.getIdempotencyKey()).isEqualTo("Payment-AbC-123");
        assertThat(record.getOperationType()).isEqualTo(IdempotencyOperation.DEPOSIT);
        assertThat(record.getRequestHash()).isEqualTo(HASH);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.getResourceId()).isNull();
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.getHttpStatus()).isNull();
        assertThat(record.getCompletedAt()).isNull();
    }

    @Test
    void start_shouldRejectKeyShorterThanEightCharacters() {
        assertThatThrownBy(() -> IdempotencyRecord.start("short", IdempotencyOperation.DEPOSIT, HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void start_shouldRejectKeyLongerThanOneHundredTwentyEightCharacters() {
        assertThatThrownBy(() -> IdempotencyRecord.start("x".repeat(129), IdempotencyOperation.DEPOSIT, HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void complete_shouldCaptureReplayMetadataAndTransitionToCompleted() {
        IdempotencyRecord record = IdempotencyRecord.start(
                "deposit-123", IdempotencyOperation.DEPOSIT, HASH);
        UUID resourceId = UUID.randomUUID();
        JsonNode responseBody = JsonNodeFactory.instance.objectNode()
                .put("transactionId", resourceId.toString());

        record.complete(resourceId, 201, responseBody);

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResourceId()).isEqualTo(resourceId);
        assertThat(record.getHttpStatus()).isEqualTo(201);
        assertThat(record.getResponseBody()).isEqualTo(responseBody);
        assertThat(record.getCompletedAt()).isNotNull();
    }

    @Test
    void complete_shouldRejectSecondCompletion() {
        IdempotencyRecord record = IdempotencyRecord.start(
                "deposit-456", IdempotencyOperation.DEPOSIT, HASH);
        record.complete(UUID.randomUUID(), 201, JsonNodeFactory.instance.objectNode());

        assertThatThrownBy(() -> record.complete(
                UUID.randomUUID(), 201, JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void complete_shouldRejectMissingReplayMetadata() {
        IdempotencyRecord record = IdempotencyRecord.start(
                "transfer-123", IdempotencyOperation.TRANSFER, HASH);

        assertThatThrownBy(() -> record.complete(UUID.randomUUID(), 201, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
