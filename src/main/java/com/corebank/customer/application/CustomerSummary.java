package com.corebank.customer.application;

import com.corebank.customer.domain.Customer;

import java.time.Instant;
import java.util.UUID;

public record CustomerSummary(UUID id, String name, Instant createdAt) {
    public static CustomerSummary from(Customer customer) {
        return new CustomerSummary(customer.getId(), customer.getName(), customer.getCreatedAt());
    }
}
