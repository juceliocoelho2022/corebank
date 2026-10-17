package com.corebank.transaction.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.event.application.FinancialEventFactory;
import com.corebank.event.application.OutboxService;
import com.corebank.ledger.application.LedgerService;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class TransactionServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock AccountTransactionRepository transactionRepository;
    @Mock LedgerService ledgerService;
    @Mock FinancialEventFactory eventFactory;
    @Mock OutboxService outboxService;

    @Test
    void successfulDeposit_shouldPersistHistoryBeforePostingLedgerWithTransactionId() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Account account = accountWithId(accountId);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(account)).thenReturn(account);
        when(transactionRepository.saveAndFlush(any(AccountTransaction.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), transactionId));

        AccountTransaction result = service().deposit(accountId, new BigDecimal("100.00"), " Initial deposit ");

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        assertThat(result.getId()).isEqualTo(transactionId);
        assertThat(result.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(result.getBalanceBefore()).isEqualByComparingTo("0.00");
        assertThat(result.getBalanceAfter()).isEqualByComparingTo("100.00");
        assertThat(result.getDescription()).isEqualTo("Initial deposit");

        InOrder order = inOrder(transactionRepository, ledgerService);
        order.verify(transactionRepository).saveAndFlush(any(AccountTransaction.class));
        order.verify(ledgerService).postDeposit(
                accountId, transactionId, new BigDecimal("100.00"), "Initial deposit");
    }

    @Test
    void successfulWithdrawal_shouldPostLedgerWithPersistedTransactionId() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Account account = accountWithId(accountId);
        account.deposit(new BigDecimal("100.00"));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(account)).thenReturn(account);
        when(transactionRepository.saveAndFlush(any(AccountTransaction.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), transactionId));

        AccountTransaction result = service().withdraw(accountId, new BigDecimal("30.00"), "ATM");

        assertThat(account.getBalance()).isEqualByComparingTo("70.00");
        assertThat(result.getId()).isEqualTo(transactionId);
        assertThat(result.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(result.getBalanceBefore()).isEqualByComparingTo("100.00");
        assertThat(result.getBalanceAfter()).isEqualByComparingTo("70.00");
        verify(ledgerService).postWithdrawal(
                accountId, transactionId, new BigDecimal("30.00"), "ATM");
    }

    @Test
    void failedWithdrawal_shouldNotCreateTransactionHistoryOrLedgerJournal() {
        UUID accountId = UUID.randomUUID();
        Account account = accountWithId(accountId);
        account.deposit(new BigDecimal("20.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service().withdraw(accountId, new BigDecimal("50.00"), null))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(accountRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(ledgerService, never()).postWithdrawal(any(), any(), any(), any());
    }

    @Test
    void history_shouldFail_whenAccountDoesNotExist() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.existsById(accountId)).thenReturn(false);

        assertThatThrownBy(() -> service().listByAccount(accountId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account not found");

        verify(transactionRepository, never()).findByAccountIdOrderByCreatedAtDesc(any());
    }

    @Test
    void history_shouldReturnRepositoryOrder() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.existsById(accountId)).thenReturn(true);
        AccountTransaction newest = new AccountTransaction(accountId, TransactionType.WITHDRAWAL,
                new BigDecimal("10.00"), new BigDecimal("100.00"), new BigDecimal("90.00"), null);
        AccountTransaction oldest = new AccountTransaction(accountId, TransactionType.DEPOSIT,
                new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("100.00"), null);
        when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId))
                .thenReturn(List.of(newest, oldest));

        assertThat(service().listByAccount(accountId)).containsExactly(newest, oldest);
    }

    @Test
    void description_shouldBeTrimmedAndWhitespaceOnlyBecomeNull() {
        UUID accountId = UUID.randomUUID();
        Account account = accountWithId(accountId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(account)).thenReturn(account);
        when(transactionRepository.saveAndFlush(any(AccountTransaction.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), UUID.randomUUID()));

        TransactionService service = service();
        AccountTransaction first = service.deposit(accountId, new BigDecimal("10.00"), "  salary  ");
        AccountTransaction second = service.deposit(accountId, new BigDecimal("5.00"), "   ");

        assertThat(first.getDescription()).isEqualTo("salary");
        assertThat(second.getDescription()).isNull();
    }

    private TransactionService service() {
        return new TransactionService(
                accountRepository,
                transactionRepository,
                ledgerService,
                eventFactory,
                outboxService);
    }

    private Account accountWithId(UUID id) {
        Account account = new Account(UUID.randomUUID(), "10000001");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private AccountTransaction withId(AccountTransaction transaction, UUID id) {
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }
}
