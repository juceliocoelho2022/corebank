package com.corebank.customer.application;

import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CustomerSummaryServiceTest {
    @Test
    void mapsOnlySafeCustomerFields() {
        CustomerRepository repository = mock(CustomerRepository.class);
        Customer customer = mock(Customer.class);
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T12:00:00Z");
        when(customer.getId()).thenReturn(id);
        when(customer.getName()).thenReturn("Ana Silva");
        when(customer.getCreatedAt()).thenReturn(createdAt);
        when(repository.findById(id)).thenReturn(Optional.of(customer));
        CustomerSummary result = new CustomerSummaryService(repository).get(id);
        assertThat(result).isEqualTo(new CustomerSummary(id, "Ana Silva", createdAt));
        assertThat(CustomerSummary.class.getRecordComponents()).extracting("name")
                .containsExactly("id", "name", "createdAt");
    }

    @Test
    void missingCustomerIsNotCachedAsNull() {
        CustomerRepository repository = mock(CustomerRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new CustomerSummaryService(repository).get(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
