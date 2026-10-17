package com.corebank.idempotency.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint();

    @Test
    void equivalentMoneyScalesAndDescriptions_shouldFingerprintIdentically() {
        UUID accountId = UUID.randomUUID();

        String first = fingerprint.deposit(accountId, new BigDecimal("100"), " salary ");
        String second = fingerprint.deposit(accountId, new BigDecimal("100.00"), "salary");

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    @Test
    void nullAndBlankDescriptions_shouldFingerprintIdentically() {
        UUID accountId = UUID.randomUUID();

        assertThat(fingerprint.withdrawal(accountId, new BigDecimal("10.00"), null))
                .isEqualTo(fingerprint.withdrawal(accountId, new BigDecimal("10.0"), "   "));
    }

    @Test
    void operationAndPayloadChanges_shouldChangeFingerprint() {
        UUID source = UUID.randomUUID();
        UUID destination = UUID.randomUUID();

        String deposit = fingerprint.deposit(source, new BigDecimal("10.00"), "x");
        String withdrawal = fingerprint.withdrawal(source, new BigDecimal("10.00"), "x");
        String transfer = fingerprint.transfer(source, destination, new BigDecimal("10.00"), "x");
        String differentAmount = fingerprint.transfer(source, destination, new BigDecimal("11.00"), "x");

        assertThat(deposit).isNotEqualTo(withdrawal);
        assertThat(withdrawal).isNotEqualTo(transfer);
        assertThat(transfer).isNotEqualTo(differentAmount);
    }
}
