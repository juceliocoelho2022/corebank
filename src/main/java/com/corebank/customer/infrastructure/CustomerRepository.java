package com.corebank.customer.infrastructure;

import com.corebank.customer.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByDocument(String document);
    boolean existsByEmailIgnoreCase(String email);
}
