package com.corebank.customer.web;

import com.corebank.customer.application.CustomerSummary;

import java.time.Instant;
import java.util.UUID;

public record CustomerSummaryResponse(UUID id, String name, Instant createdAt) {
    public static CustomerSummaryResponse from(CustomerSummary summary) {
        return new CustomerSummaryResponse(summary.id(), summary.name(), summary.createdAt());
    }
}
