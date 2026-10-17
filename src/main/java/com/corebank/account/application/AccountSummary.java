package com.corebank.account.application;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountSummary(UUID id, UUID customerId, String branch, String number,
                             AccountStatus status, String currency, Instant createdAt) {
    public static AccountSummary from(Account account) {
        return new AccountSummary(account.getId(), account.getCustomerId(), account.getBranch(),
                account.getNumber(), account.getStatus(), account.getCurrency(), account.getCreatedAt());
    }
}
