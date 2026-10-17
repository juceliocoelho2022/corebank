package com.corebank.account.web;

import com.corebank.account.domain.Account;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(UUID accountId, BigDecimal balance, String currency) {
    public static BalanceResponse from(Account account) {
        return new BalanceResponse(account.getId(), account.getBalance(), account.getCurrency());
    }
}
