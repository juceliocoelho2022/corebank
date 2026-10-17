package com.corebank.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    @Test
    void balancedPair_shouldBeAccepted() {
        UUID journalId = UUID.randomUUID();
        JournalEntry journal = new JournalEntry(UUID.randomUUID(), JournalReferenceType.DEPOSIT, "deposit");
        LedgerEntry debit = new LedgerEntry(journalId, UUID.randomUUID(), LedgerSide.DEBIT, new BigDecimal("100.00"));
        LedgerEntry credit = new LedgerEntry(journalId, UUID.randomUUID(), LedgerSide.CREDIT, new BigDecimal("100.00"));

        assertThatCode(() -> journal.validateBalanced(List.of(debit, credit)))
                .doesNotThrowAnyException();
    }

    @Test
    void unbalancedPair_shouldBeRejected() {
        UUID journalId = UUID.randomUUID();
        JournalEntry journal = new JournalEntry(UUID.randomUUID(), JournalReferenceType.DEPOSIT, "deposit");
        LedgerEntry debit = new LedgerEntry(journalId, UUID.randomUUID(), LedgerSide.DEBIT, new BigDecimal("100.00"));
        LedgerEntry credit = new LedgerEntry(journalId, UUID.randomUUID(), LedgerSide.CREDIT, new BigDecimal("99.99"));

        assertThatThrownBy(() -> journal.validateBalanced(List.of(debit, credit)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void ledgerEntry_shouldRejectZeroOrNegativeAmount() {
        UUID journalId = UUID.randomUUID();
        UUID ledgerAccountId = UUID.randomUUID();

        assertThatThrownBy(() -> new LedgerEntry(journalId, ledgerAccountId, LedgerSide.DEBIT, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerEntry(journalId, ledgerAccountId, LedgerSide.CREDIT, new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerEntry_shouldRejectAmountWithMoreThanTwoDecimals() {
        assertThatThrownBy(() -> new LedgerEntry(
                UUID.randomUUID(), UUID.randomUUID(), LedgerSide.DEBIT, new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customerLiability_shouldRequireAccountId() {
        assertThatThrownBy(() -> new LedgerAccount("CUSTOMER:missing", LedgerAccountType.CUSTOMER_LIABILITY, null, "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cashClearing_shouldRejectAccountId() {
        assertThatThrownBy(() -> new LedgerAccount(
                "TECH:CASH_CLEARING", LedgerAccountType.CASH_CLEARING, UUID.randomUUID(), "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
