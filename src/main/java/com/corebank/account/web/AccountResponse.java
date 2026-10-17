package com.corebank.account.web;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID customerId,
        String branch,
        String number,
        AccountStatus status,
        BigDecimal balance,
        String currency,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCustomerId(),
                account.getBranch(),
                account.getNumber(),
                account.getStatus(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt());
    }
}
