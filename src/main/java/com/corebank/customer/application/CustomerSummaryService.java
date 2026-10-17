package com.corebank.customer.application;

import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerSummaryService {
    private final CustomerRepository repository;

    public CustomerSummaryService(CustomerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "customer-summary", key = "#id.toString()")
    public CustomerSummary get(UUID id) {
        return repository.findById(id).map(CustomerSummary::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }
}
