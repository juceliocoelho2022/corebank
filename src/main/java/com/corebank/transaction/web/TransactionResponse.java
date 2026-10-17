package com.corebank.transaction.web;

import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String description,
        UUID transferId,
        UUID counterpartyAccountId,
        Instant createdAt) {

    public static TransactionResponse from(AccountTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getBalanceBefore(),
                transaction.getBalanceAfter(),
                transaction.getDescription(),
                transaction.getTransferId(),
                transaction.getCounterpartyAccountId(),
                transaction.getCreatedAt());
    }
}
