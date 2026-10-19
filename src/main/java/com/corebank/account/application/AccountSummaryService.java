package com.corebank.account.application;

import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountSummaryService {
    private final AccountRepository repository;

    public AccountSummaryService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "account-summary", key = "#id.toString()")
    public AccountSummary get(UUID id) {
        return repository.findById(id).map(AccountSummary::from)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
