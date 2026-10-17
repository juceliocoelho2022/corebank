package com.corebank.account.application;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.infrastructure.CustomerRepository;
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

    public AccountService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            AccountNumberGenerator numberGenerator) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.numberGenerator = numberGenerator;
    }

    @Transactional
    public Account create(UUID customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found");
        }

        Account account = new Account(customerId, numberGenerator.nextNumber());
        try {
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Account could not be created because of an integrity conflict");
        }
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
