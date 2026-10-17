package com.corebank.reconciliation.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.ledger.domain.LedgerAccount;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconciliationService(AccountRepository accountRepository,
                                 LedgerAccountRepository ledgerAccountRepository,
                                 LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public ReconciliationResult reconcile(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        LedgerAccount ledgerAccount = ledgerAccountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Ledger account not found"));

        BigDecimal operationalBalance = money(account.getBalance());
        BigDecimal ledgerBalance = money(ledgerEntryRepository.calculateLiabilityBalance(ledgerAccount.getId()));
        BigDecimal difference = money(operationalBalance.subtract(ledgerBalance));
        ReconciliationStatus status = difference.signum() == 0
                ? ReconciliationStatus.RECONCILED
                : ReconciliationStatus.MISMATCH;

        return new ReconciliationResult(
                accountId,
                operationalBalance,
                ledgerBalance,
                difference,
                status);
    }

    private static BigDecimal money(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(2, RoundingMode.UNNECESSARY);
    }
}
