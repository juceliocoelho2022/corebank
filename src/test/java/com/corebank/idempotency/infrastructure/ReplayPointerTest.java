package com.corebank.idempotency.infrastructure;

import com.corebank.idempotency.domain.IdempotencyOperation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ReplayPointerTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void supportsSafeCompletedMetadataAndNullableResourceId() {
        UUID id = UUID.randomUUID();
        var pointer = new ReplayPointer(id, IdempotencyOperation.DEPOSIT, HASH, null, 201);
        assertThat(pointer.recordId()).isEqualTo(id);
        assertThat(pointer.resourceId()).isNull();
        assertThat(ReplayPointer.class.getRecordComponents()).extracting("name")
                .containsExactly("recordId", "operation", "requestHash", "resourceId", "httpStatus");
    }

    @Test
    void rejectsMalformedPointers() {
        assertThatThrownBy(() -> new ReplayPointer(null, IdempotencyOperation.DEPOSIT, HASH, null, 200))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReplayPointer(UUID.randomUUID(), null, HASH, null, 200))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReplayPointer(UUID.randomUUID(), IdempotencyOperation.DEPOSIT, "ABC", null, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplayPointer(UUID.randomUUID(), IdempotencyOperation.DEPOSIT, HASH, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
