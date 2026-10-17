package com.corebank.reconciliation.application;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationResult(
        UUID accountId,
        BigDecimal operationalBalance,
        BigDecimal ledgerBalance,
        BigDecimal difference,
        ReconciliationStatus status
) {
}
