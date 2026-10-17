package com.corebank.account.web;

import com.corebank.account.application.AccountSummary;
import com.corebank.account.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountSummaryResponse(UUID id, UUID customerId, String branch, String number,
                                     AccountStatus status, String currency, Instant createdAt) {
    public static AccountSummaryResponse from(AccountSummary summary) {
        return new AccountSummaryResponse(summary.id(), summary.customerId(), summary.branch(),
                summary.number(), summary.status(), summary.currency(), summary.createdAt());
    }
}
