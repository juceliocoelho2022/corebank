package com.corebank.transaction.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.event.application.FinancialEventFactory;
import com.corebank.event.application.OutboxService;
import com.corebank.ledger.application.LedgerService;
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
    private final LedgerService ledgerService;
    private final FinancialEventFactory eventFactory;
    private final OutboxService outboxService;

    public TransactionService(
            AccountRepository accountRepository,
            AccountTransactionRepository transactionRepository,
            LedgerService ledgerService,
            FinancialEventFactory eventFactory,
            OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.eventFactory = eventFactory;
        this.outboxService = outboxService;
    }

    @Transactional
    public AccountTransaction deposit(UUID accountId, BigDecimal amount, String description) {
        Account account = getAccount(accountId);
        BigDecimal before = account.deposit(amount);
        accountRepository.saveAndFlush(account);

        AccountTransaction transaction = new AccountTransaction(
                accountId, TransactionType.DEPOSIT, amount, before, account.getBalance(), description);
        AccountTransaction persisted = transactionRepository.saveAndFlush(transaction);

        ledgerService.postDeposit(
                accountId, persisted.getId(), persisted.getAmount(), persisted.getDescription());
        outboxService.record(eventFactory.depositCompleted(persisted));

        return persisted;
    }

    @Transactional
    public AccountTransaction withdraw(UUID accountId, BigDecimal amount, String description) {
        Account account = getAccount(accountId);
        BigDecimal before = account.withdraw(amount);
        accountRepository.saveAndFlush(account);

        AccountTransaction transaction = new AccountTransaction(
                accountId, TransactionType.WITHDRAWAL, amount, before, account.getBalance(), description);
        AccountTransaction persisted = transactionRepository.saveAndFlush(transaction);

        ledgerService.postWithdrawal(
                accountId, persisted.getId(), persisted.getAmount(), persisted.getDescription());
        outboxService.record(eventFactory.withdrawalCompleted(persisted));

        return persisted;
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> listByAccount(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResourceNotFoundException("Account not found");
        }
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    private Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
