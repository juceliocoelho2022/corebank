package com.corebank.ledger.application;

import com.corebank.ledger.domain.JournalEntry;
import com.corebank.ledger.domain.JournalReferenceType;
import com.corebank.ledger.domain.LedgerAccount;
import com.corebank.ledger.domain.LedgerEntry;
import com.corebank.ledger.domain.LedgerSide;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private static final String CASH_CLEARING_CODE = "TECH:CASH_CLEARING";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(
            LedgerAccountRepository ledgerAccountRepository,
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public JournalEntry postDeposit(
            UUID accountId,
            UUID transactionId,
            BigDecimal amount,
            String description) {
        LedgerAccount clearing = requireByCode(CASH_CLEARING_CODE);
        LedgerAccount customer = requireByAccountId(accountId);

        return post(
                transactionId,
                JournalReferenceType.DEPOSIT,
                description,
                clearing,
                LedgerSide.DEBIT,
                customer,
                LedgerSide.CREDIT,
                amount);
    }

    @Transactional
    public JournalEntry postWithdrawal(
            UUID accountId,
            UUID transactionId,
            BigDecimal amount,
            String description) {
        LedgerAccount customer = requireByAccountId(accountId);
        LedgerAccount clearing = requireByCode(CASH_CLEARING_CODE);

        return post(
                transactionId,
                JournalReferenceType.WITHDRAWAL,
                description,
                customer,
                LedgerSide.DEBIT,
                clearing,
                LedgerSide.CREDIT,
                amount);
    }

    @Transactional
    public JournalEntry postTransfer(
            UUID sourceAccountId,
            UUID destinationAccountId,
            UUID transferId,
            BigDecimal amount,
            String description) {
        LedgerAccount source = requireByAccountId(sourceAccountId);
        LedgerAccount destination = requireByAccountId(destinationAccountId);

        return post(
                transferId,
                JournalReferenceType.TRANSFER,
                description,
                source,
                LedgerSide.DEBIT,
                destination,
                LedgerSide.CREDIT,
                amount);
    }

    private JournalEntry post(
            UUID referenceId,
            JournalReferenceType referenceType,
            String description,
            LedgerAccount firstAccount,
            LedgerSide firstSide,
            LedgerAccount secondAccount,
            LedgerSide secondSide,
            BigDecimal amount) {
        JournalEntry journal = journalEntryRepository.saveAndFlush(
                new JournalEntry(referenceId, referenceType, description));

        List<LedgerEntry> entries = List.of(
                new LedgerEntry(journal.getId(), firstAccount.getId(), firstSide, amount),
                new LedgerEntry(journal.getId(), secondAccount.getId(), secondSide, amount));

        journal.validateBalanced(entries);
        ledgerEntryRepository.saveAll(entries);
        return journal;
    }

    private LedgerAccount requireByAccountId(UUID accountId) {
        return ledgerAccountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Customer ledger account not found for account " + accountId));
    }

    private LedgerAccount requireByCode(String code) {
        return ledgerAccountRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException(
                        "Technical ledger account not found: " + code));
    }
}
