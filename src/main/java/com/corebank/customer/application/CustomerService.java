package com.corebank.customer.application;

import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.shared.exception.ConflictException;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Customer create(String name, String document, String email) {
        String normalizedName = name.trim();
        String normalizedDocument = document.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (repository.existsByDocument(normalizedDocument)) {
            throw new ConflictException("A customer with this document already exists");
        }
        if (repository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("A customer with this email already exists");
        }

        try {
            return repository.save(new Customer(normalizedName, normalizedDocument, normalizedEmail));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Customer document or email already exists");
        }
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }
}
