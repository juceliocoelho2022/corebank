package com.corebank.customer.application;

import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.shared.exception.ConflictException;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    CustomerRepository repository;

    @Test
    void createsCustomerAndNormalizesEmail() {
        when(repository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CustomerService service = new CustomerService(repository);

        Customer created = service.create(" Ana Silva ", " 12345678901 ", " Ana.Silva@Example.COM ");

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(repository).save(captor.capture());
        assertThat(created).isSameAs(captor.getValue());
        assertThat(captor.getValue().getName()).isEqualTo("Ana Silva");
        assertThat(captor.getValue().getDocument()).isEqualTo("12345678901");
        assertThat(captor.getValue().getEmail()).isEqualTo("ana.silva@example.com");
    }

    @Test
    void rejectsDuplicateDocument() {
        when(repository.existsByDocument("12345678901")).thenReturn(true);
        CustomerService service = new CustomerService(repository);

        assertThatThrownBy(() -> service.create("Ana Silva", "12345678901", "ana@example.com"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A customer with this document already exists");

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsDuplicateEmail() {
        when(repository.existsByDocument("12345678901")).thenReturn(false);
        when(repository.existsByEmailIgnoreCase("ana@example.com")).thenReturn(true);
        CustomerService service = new CustomerService(repository);

        assertThatThrownBy(() -> service.create("Ana Silva", "12345678901", "ANA@EXAMPLE.COM"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A customer with this email already exists");

        verify(repository, never()).save(any());
    }

    @Test
    void throwsWhenCustomerDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        CustomerService service = new CustomerService(repository);

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Customer not found");
    }
}
