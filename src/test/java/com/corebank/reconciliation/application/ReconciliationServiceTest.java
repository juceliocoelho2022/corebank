package com.corebank.reconciliation.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock LedgerAccountRepository ledgerAccountRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;

    @Test
    void equalOperationalAndLiabilityBalance_shouldBeReconciled() {
        UUID accountId = UUID.randomUUID();
        UUID ledgerAccountId = UUID.randomUUID();
        Account account = account(accountId, "150.00");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(ledgerAccountRepository.findByAccountId(accountId))
                .thenReturn(Optional.of(ledgerAccount(ledgerAccountId, accountId)));
        when(ledgerEntryRepository.calculateLiabilityBalance(ledgerAccountId))
                .thenReturn(new BigDecimal("150.00"));

        ReconciliationResult result = service().reconcile(accountId);

        assertThat(result.accountId()).isEqualTo(accountId);
        assertThat(result.operationalBalance()).isEqualByComparingTo("150.00");
        assertThat(result.ledgerBalance()).isEqualByComparingTo("150.00");
        assertThat(result.difference()).isEqualByComparingTo("0.00");
        assertThat(result.status()).isEqualTo(ReconciliationStatus.RECONCILED);
    }

    @Test
    void liabilityBalance_shouldUseCreditsMinusDebitsAndReportMismatch() {
        UUID accountId = UUID.randomUUID();
        UUID ledgerAccountId = UUID.randomUUID();
        Account account = account(accountId, "120.00");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(ledgerAccountRepository.findByAccountId(accountId))
                .thenReturn(Optional.of(ledgerAccount(ledgerAccountId, accountId)));
        when(ledgerEntryRepository.calculateLiabilityBalance(ledgerAccountId))
                .thenReturn(new BigDecimal("100.00"));

        ReconciliationResult result = service().reconcile(accountId);

        assertThat(result.operationalBalance()).isEqualByComparingTo("120.00");
        assertThat(result.ledgerBalance()).isEqualByComparingTo("100.00");
        assertThat(result.difference()).isEqualByComparingTo("20.00");
        assertThat(result.status()).isEqualTo(ReconciliationStatus.MISMATCH);
        verify(ledgerEntryRepository).calculateLiabilityBalance(ledgerAccountId);
    }

    @Test
    void noLedgerEntries_shouldProduceZeroLedgerBalance() {
        UUID accountId = UUID.randomUUID();
        UUID ledgerAccountId = UUID.randomUUID();
        Account account = account(accountId, "0.00");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(ledgerAccountRepository.findByAccountId(accountId))
                .thenReturn(Optional.of(ledgerAccount(ledgerAccountId, accountId)));
        when(ledgerEntryRepository.calculateLiabilityBalance(ledgerAccountId))
                .thenReturn(BigDecimal.ZERO);

        ReconciliationResult result = service().reconcile(accountId);

        assertThat(result.ledgerBalance()).isEqualByComparingTo("0.00");
        assertThat(result.difference()).isEqualByComparingTo("0.00");
        assertThat(result.status()).isEqualTo(ReconciliationStatus.RECONCILED);
    }

    @Test
    void missingAccount_shouldReturnNotFoundBeforeLedgerLookup() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().reconcile(accountId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account not found");
    }

    private ReconciliationService service() {
        return new ReconciliationService(accountRepository, ledgerAccountRepository, ledgerEntryRepository);
    }

    private Account account(UUID id, String balance) {
        Account account = new Account(UUID.randomUUID(), "10000001");
        ReflectionTestUtils.setField(account, "id", id);
        if (new BigDecimal(balance).signum() > 0) {
            account.deposit(new BigDecimal(balance));
        }
        return account;
    }

    private com.corebank.ledger.domain.LedgerAccount ledgerAccount(UUID id, UUID accountId) {
        var ledger = com.corebank.ledger.domain.LedgerAccount.customerLiability(accountId);
        ReflectionTestUtils.setField(ledger, "id", id);
        return ledger;
    }
}
