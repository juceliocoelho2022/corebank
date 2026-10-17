package com.corebank.transfer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResult(
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter,
        Instant createdAt) {
}
