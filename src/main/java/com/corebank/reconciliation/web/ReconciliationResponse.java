package com.corebank.reconciliation.web;

import com.corebank.reconciliation.application.ReconciliationResult;
import com.corebank.reconciliation.application.ReconciliationStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationResponse(
        UUID accountId,
        BigDecimal operationalBalance,
        BigDecimal ledgerBalance,
        BigDecimal difference,
        ReconciliationStatus status
) {
    public static ReconciliationResponse from(ReconciliationResult result) {
        return new ReconciliationResponse(
                result.accountId(),
                result.operationalBalance(),
                result.ledgerBalance(),
                result.difference(),
                result.status());
    }
}
