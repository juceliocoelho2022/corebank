package com.corebank.account.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.ledger.domain.LedgerAccount;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import com.corebank.shared.exception.ConflictException;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final AccountNumberGenerator numberGenerator;
    private final LedgerAccountRepository ledgerAccountRepository;

    public AccountService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            AccountNumberGenerator numberGenerator,
            LedgerAccountRepository ledgerAccountRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.numberGenerator = numberGenerator;
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    @Transactional
    public Account create(UUID customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found");
        }

        Account account = new Account(customerId, numberGenerator.nextNumber());
        Account persisted;
        try {
            persisted = accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Account could not be created because of an integrity conflict");
        }

        ledgerAccountRepository.saveAndFlush(LedgerAccount.customerLiability(persisted.getId()));
        return persisted;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
