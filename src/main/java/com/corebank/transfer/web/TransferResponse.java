package com.corebank.transfer.web;

import com.corebank.transfer.application.TransferResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter,
        Instant createdAt) {

    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(
                result.transferId(),
                result.sourceAccountId(),
                result.destinationAccountId(),
                result.amount(),
                result.sourceBalanceAfter(),
                result.destinationBalanceAfter(),
                result.createdAt());
    }
}
