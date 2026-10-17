package com.corebank.transaction.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository, AccountTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public AccountTransaction deposit(UUID accountId, BigDecimal amount, String description) {
        Account account = getAccount(accountId);
        BigDecimal before = account.deposit(amount);
        accountRepository.saveAndFlush(account);
        AccountTransaction transaction = new AccountTransaction(accountId, TransactionType.DEPOSIT, amount, before, account.getBalance(), description);
        return transactionRepository.save(transaction);
    }

    @Transactional
    public AccountTransaction withdraw(UUID accountId, BigDecimal amount, String description) {
        Account account = getAccount(accountId);
        BigDecimal before = account.withdraw(amount);
        accountRepository.saveAndFlush(account);
        AccountTransaction transaction = new AccountTransaction(accountId, TransactionType.WITHDRAWAL, amount, before, account.getBalance(), description);
        return transactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> listByAccount(UUID accountId) {
        if (!accountRepository.existsById(accountId)) throw new ResourceNotFoundException("Account not found");
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    private Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
