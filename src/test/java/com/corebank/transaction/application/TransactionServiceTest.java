package com.corebank.transaction.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {
    @Mock AccountRepository accountRepository;
    @Mock AccountTransactionRepository transactionRepository;

    @Test void successfulDeposit_shouldPersistAccountAndTransaction(){ UUID id=UUID.randomUUID(); Account a=accountWithId(id); when(accountRepository.findById(id)).thenReturn(Optional.of(a)); when(accountRepository.saveAndFlush(a)).thenReturn(a); when(transactionRepository.save(any(AccountTransaction.class))).thenAnswer(i->i.getArgument(0)); AccountTransaction r=service().deposit(id,new BigDecimal("100.00")," Initial deposit "); assertThat(a.getBalance()).isEqualByComparingTo("100.00"); assertThat(r.getType()).isEqualTo(TransactionType.DEPOSIT); assertThat(r.getBalanceBefore()).isEqualByComparingTo("0.00"); assertThat(r.getBalanceAfter()).isEqualByComparingTo("100.00"); assertThat(r.getDescription()).isEqualTo("Initial deposit"); verify(accountRepository).saveAndFlush(a); verify(transactionRepository).save(any(AccountTransaction.class)); }
    @Test void successfulWithdrawal_shouldPersistAccountAndTransaction(){ UUID id=UUID.randomUUID(); Account a=accountWithId(id); a.deposit(new BigDecimal("100.00")); when(accountRepository.findById(id)).thenReturn(Optional.of(a)); when(accountRepository.saveAndFlush(a)).thenReturn(a); when(transactionRepository.save(any(AccountTransaction.class))).thenAnswer(i->i.getArgument(0)); AccountTransaction r=service().withdraw(id,new BigDecimal("30.00"),"ATM"); assertThat(a.getBalance()).isEqualByComparingTo("70.00"); assertThat(r.getType()).isEqualTo(TransactionType.WITHDRAWAL); assertThat(r.getBalanceBefore()).isEqualByComparingTo("100.00"); assertThat(r.getBalanceAfter()).isEqualByComparingTo("70.00"); }
    @Test void failedWithdrawal_shouldNotCreateTransactionHistory(){ UUID id=UUID.randomUUID(); Account a=accountWithId(id); a.deposit(new BigDecimal("20.00")); when(accountRepository.findById(id)).thenReturn(Optional.of(a)); assertThatThrownBy(()->service().withdraw(id,new BigDecimal("50.00"),null)).isInstanceOf(InsufficientBalanceException.class); verify(accountRepository,never()).saveAndFlush(any()); verify(transactionRepository,never()).save(any()); }
    @Test void history_shouldFail_whenAccountDoesNotExist(){ UUID id=UUID.randomUUID(); when(accountRepository.existsById(id)).thenReturn(false); assertThatThrownBy(()->service().listByAccount(id)).isInstanceOf(ResourceNotFoundException.class).hasMessage("Account not found"); verify(transactionRepository,never()).findByAccountIdOrderByCreatedAtDesc(any()); }
    @Test void history_shouldReturnRepositoryOrder(){ UUID id=UUID.randomUUID(); when(accountRepository.existsById(id)).thenReturn(true); AccountTransaction newest=new AccountTransaction(id,TransactionType.WITHDRAWAL,new BigDecimal("10.00"),new BigDecimal("100.00"),new BigDecimal("90.00"),null); AccountTransaction oldest=new AccountTransaction(id,TransactionType.DEPOSIT,new BigDecimal("100.00"),new BigDecimal("0.00"),new BigDecimal("100.00"),null); when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(id)).thenReturn(List.of(newest,oldest)); assertThat(service().listByAccount(id)).containsExactly(newest,oldest); }
    @Test void description_shouldBeTrimmedAndWhitespaceOnlyBecomeNull(){ UUID id=UUID.randomUUID(); Account a=accountWithId(id); when(accountRepository.findById(id)).thenReturn(Optional.of(a)); when(accountRepository.saveAndFlush(a)).thenReturn(a); when(transactionRepository.save(any(AccountTransaction.class))).thenAnswer(i->i.getArgument(0)); TransactionService s=service(); AccountTransaction first=s.deposit(id,new BigDecimal("10.00"),"  salary  "); AccountTransaction second=s.deposit(id,new BigDecimal("5.00"),"   "); assertThat(first.getDescription()).isEqualTo("salary"); assertThat(second.getDescription()).isNull(); }
    private TransactionService service(){ return new TransactionService(accountRepository,transactionRepository); }
    private Account accountWithId(UUID id){ Account a=new Account(UUID.randomUUID(),"10000001"); org.springframework.test.util.ReflectionTestUtils.setField(a,"id",id); return a; }
}
