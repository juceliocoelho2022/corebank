package com.corebank.idempotency.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyValidatorTest {

    private final IdempotencyKeyValidator validator = new IdempotencyKeyValidator();

    @Test
    void shouldReturnOpaqueValidKeyUnchanged() {
        assertThat(validator.validate("Payment-AbC-123")).isEqualTo("Payment-AbC-123");
    }

    @Test
    void shouldAcceptBoundaryLengths() {
        assertThat(validator.validate("x".repeat(8))).hasSize(8);
        assertThat(validator.validate("x".repeat(128))).hasSize(128);
    }

    @Test
    void shouldRejectMissingShortAndLongKeys() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }
}
