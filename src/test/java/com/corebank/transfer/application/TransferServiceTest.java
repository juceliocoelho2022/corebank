package com.corebank.transfer.application;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.ledger.application.LedgerService;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAccountStateException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock AccountTransactionRepository transactionRepository;
    @Mock LedgerService ledgerService;

    @Test
    void transfer_shouldMoveMoneyCreateCorrelatedHistoryAndPostLedgerWithSameTransferId() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Account source = account(sourceId, "10000001", "100.00");
        Account destination = account(destinationId, "10000002", "20.00");
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any(AccountTransaction.class))).thenAnswer(i -> i.getArgument(0));

        TransferResult result = service().transfer(sourceId, destinationId, new BigDecimal("30.00"), "  Rent  ");

        assertThat(source.getBalance()).isEqualByComparingTo("70.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("50.00");
        assertThat(result.amount()).isEqualByComparingTo("30.00");
        assertThat(result.sourceBalanceAfter()).isEqualByComparingTo("70.00");
        assertThat(result.destinationBalanceAfter()).isEqualByComparingTo("50.00");
        assertThat(result.transferId()).isNotNull();

        ArgumentCaptor<AccountTransaction> captor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<AccountTransaction> entries = captor.getAllValues();
        AccountTransaction out = entries.get(0);
        AccountTransaction in = entries.get(1);
        assertThat(out.getType()).isEqualTo(TransactionType.TRANSFER_OUT);
        assertThat(in.getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(out.getTransferId()).isEqualTo(result.transferId());
        assertThat(in.getTransferId()).isEqualTo(result.transferId());
        assertThat(out.getCounterpartyAccountId()).isEqualTo(destinationId);
        assertThat(in.getCounterpartyAccountId()).isEqualTo(sourceId);
        assertThat(out.getDescription()).isEqualTo("Rent");
        assertThat(in.getDescription()).isEqualTo("Rent");

        InOrder order = inOrder(transactionRepository, ledgerService);
        order.verify(transactionRepository, org.mockito.Mockito.times(2)).save(any(AccountTransaction.class));
        order.verify(ledgerService).postTransfer(
                sourceId, destinationId, result.transferId(), new BigDecimal("30.00"), "Rent");
    }

    @Test
    void transfer_shouldRejectSameAccountBeforeLookupOrMutation() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service().transfer(id, id, new BigDecimal("10.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source and destination accounts must be different");
        verify(accountRepository, never()).findById(any());
        verify(transactionRepository, never()).save(any());
        verify(ledgerService, never()).postTransfer(any(), any(), any(), any(), any());
    }

    @Test
    void transfer_shouldReturnNotFoundForMissingSourceOrDestination() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(accountRepository.findById(sourceId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, new BigDecimal("10.00"), null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Source account not found");

        Account source = account(sourceId, "10000003", "100.00");
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, new BigDecimal("10.00"), null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Destination account not found");
    }

    @Test
    void transfer_shouldRejectInactiveAccountsBeforeChangingBalances() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Account source = account(sourceId, "10000004", "100.00");
        Account destination = account(destinationId, "10000005", "20.00");
        ReflectionTestUtils.setField(destination, "status", AccountStatus.BLOCKED);
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, new BigDecimal("10.00"), null))
                .isInstanceOf(InvalidAccountStateException.class);
        assertThat(source.getBalance()).isEqualByComparingTo("100.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("20.00");
        verify(accountRepository, never()).saveAndFlush(any());
        verify(ledgerService, never()).postTransfer(any(), any(), any(), any(), any());
    }

    @Test
    void transfer_shouldRejectInvalidAmountAndInsufficientBalanceWithoutLedgerPosting() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Account source = account(sourceId, "10000006", "20.00");
        Account destination = account(destinationId, "10000007", "0.00");
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, new BigDecimal("1.001"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, new BigDecimal("50.00"), null))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(transactionRepository, never()).save(any());
        verify(ledgerService, never()).postTransfer(any(), any(), any(), any(), any());
    }

    @Test
    void transfer_shouldNormalizeBlankDescriptionAndRejectTooLongDescriptionBeforeMutation() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Account source = account(sourceId, "10000008", "100.00");
        Account destination = account(destinationId, "10000009", "0.00");
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any(AccountTransaction.class))).thenAnswer(i -> i.getArgument(0));

        TransferResult result = service().transfer(sourceId, destinationId, new BigDecimal("10.00"), "   ");
        ArgumentCaptor<AccountTransaction> captor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(tx -> assertThat(tx.getDescription()).isNull());
        verify(ledgerService).postTransfer(sourceId, destinationId, result.transferId(), new BigDecimal("10.00"), null);

        Account source2 = account(sourceId, "10000010", "100.00");
        Account destination2 = account(destinationId, "10000011", "0.00");
        assertThatThrownBy(() -> service().transfer(sourceId, destinationId, new BigDecimal("10.00"), "x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(source2.getBalance()).isEqualByComparingTo("100.00");
        assertThat(destination2.getBalance()).isEqualByComparingTo("0.00");
    }

    private TransferService service() {
        return new TransferService(accountRepository, transactionRepository, ledgerService);
    }

    private Account account(UUID id, String number, String balance) {
        Account account = new Account(UUID.randomUUID(), number);
        ReflectionTestUtils.setField(account, "id", id);
        if (new BigDecimal(balance).signum() > 0) account.deposit(new BigDecimal(balance));
        return account;
    }
}
