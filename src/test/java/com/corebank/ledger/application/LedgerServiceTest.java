package com.corebank.ledger.application;

import com.corebank.ledger.domain.JournalEntry;
import com.corebank.ledger.domain.JournalReferenceType;
import com.corebank.ledger.domain.LedgerAccount;
import com.corebank.ledger.domain.LedgerEntry;
import com.corebank.ledger.domain.LedgerSide;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock LedgerAccountRepository ledgerAccountRepository;
    @Mock JournalEntryRepository journalEntryRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;

    private LedgerService service;
    private LedgerAccount clearing;
    private UUID accountId;
    private LedgerAccount customer;

    @BeforeEach
    void setUp() {
        service = new LedgerService(ledgerAccountRepository, journalEntryRepository, ledgerEntryRepository);

        clearing = withId(LedgerAccount.cashClearing(), UUID.randomUUID());
        accountId = UUID.randomUUID();
        customer = withId(LedgerAccount.customerLiability(accountId), UUID.randomUUID());
    }

    @Test
    void postDeposit_shouldDebitClearingAndCreditCustomerLiability() {
        when(ledgerAccountRepository.findByCode("TECH:CASH_CLEARING")).thenReturn(Optional.of(clearing));
        when(ledgerAccountRepository.findByAccountId(accountId)).thenReturn(Optional.of(customer));
        stubJournalPersistence();

        UUID transactionId = UUID.randomUUID();

        JournalEntry journal = service.postDeposit(accountId, transactionId, new BigDecimal("100.00"), "deposit");

        assertThat(journal.getReferenceType()).isEqualTo(JournalReferenceType.DEPOSIT);
        assertThat(journal.getReferenceId()).isEqualTo(transactionId);

        List<LedgerEntry> entries = capturedEntries();
        assertThat(entries).hasSize(2);
        assertEntry(entries.get(0), clearing.getId(), LedgerSide.DEBIT, "100.00");
        assertEntry(entries.get(1), customer.getId(), LedgerSide.CREDIT, "100.00");
    }

    @Test
    void postWithdrawal_shouldDebitCustomerLiabilityAndCreditClearing() {
        when(ledgerAccountRepository.findByCode("TECH:CASH_CLEARING")).thenReturn(Optional.of(clearing));
        when(ledgerAccountRepository.findByAccountId(accountId)).thenReturn(Optional.of(customer));
        stubJournalPersistence();

        UUID transactionId = UUID.randomUUID();

        JournalEntry journal = service.postWithdrawal(accountId, transactionId, new BigDecimal("40.00"), "withdrawal");

        assertThat(journal.getReferenceType()).isEqualTo(JournalReferenceType.WITHDRAWAL);
        assertThat(journal.getReferenceId()).isEqualTo(transactionId);

        List<LedgerEntry> entries = capturedEntries();
        assertThat(entries).hasSize(2);
        assertEntry(entries.get(0), customer.getId(), LedgerSide.DEBIT, "40.00");
        assertEntry(entries.get(1), clearing.getId(), LedgerSide.CREDIT, "40.00");
    }

    @Test
    void postTransfer_shouldDebitSourceAndCreditDestinationCustomerLiability() {
        UUID destinationAccountId = UUID.randomUUID();
        LedgerAccount destination = withId(LedgerAccount.customerLiability(destinationAccountId), UUID.randomUUID());
        when(ledgerAccountRepository.findByAccountId(accountId)).thenReturn(Optional.of(customer));
        when(ledgerAccountRepository.findByAccountId(destinationAccountId)).thenReturn(Optional.of(destination));
        stubJournalPersistence();

        UUID transferId = UUID.randomUUID();
        JournalEntry journal = service.postTransfer(
                accountId, destinationAccountId, transferId, new BigDecimal("25.00"), "transfer");

        assertThat(journal.getReferenceType()).isEqualTo(JournalReferenceType.TRANSFER);
        assertThat(journal.getReferenceId()).isEqualTo(transferId);

        List<LedgerEntry> entries = capturedEntries();
        assertThat(entries).hasSize(2);
        assertEntry(entries.get(0), customer.getId(), LedgerSide.DEBIT, "25.00");
        assertEntry(entries.get(1), destination.getId(), LedgerSide.CREDIT, "25.00");
    }

    @Test
    void missingCustomerLedgerAccount_shouldFailBeforeJournalPersistence() {
        UUID missingAccountId = UUID.randomUUID();

        assertThatThrownBy(() -> service.postDeposit(
                missingAccountId, UUID.randomUUID(), new BigDecimal("10.00"), "deposit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger account");

        verify(journalEntryRepository, never()).saveAndFlush(any(JournalEntry.class));
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    private void stubJournalPersistence() {
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), UUID.randomUUID()));
    }

    private List<LedgerEntry> capturedEntries() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LedgerEntry>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        return ((List<LedgerEntry>) captor.getValue());
    }

    private static void assertEntry(LedgerEntry entry, UUID ledgerAccountId, LedgerSide side, String amount) {
        assertThat(entry.getLedgerAccountId()).isEqualTo(ledgerAccountId);
        assertThat(entry.getSide()).isEqualTo(side);
        assertThat(entry.getAmount()).isEqualByComparingTo(amount);
    }

    private static <T> T withId(T target, UUID id) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
            return target;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
