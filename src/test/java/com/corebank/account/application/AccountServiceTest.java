package com.corebank.account.application;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    CustomerRepository customerRepository;
    @Mock
    AccountNumberGenerator numberGenerator;

    @Test
    void createsAccountWithBankingDefaults() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.existsById(customerId)).thenReturn(true);
        when(numberGenerator.nextNumber()).thenReturn("10000001");
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AccountService service = new AccountService(accountRepository, customerRepository, numberGenerator);

        Account account = service.create(customerId);

        assertThat(account.getCustomerId()).isEqualTo(customerId);
        assertThat(account.getBranch()).isEqualTo("0001");
        assertThat(account.getNumber()).isEqualTo("10000001");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(account.getBalance().scale()).isEqualTo(2);
        assertThat(account.getCurrency()).isEqualTo("BRL");
    }

    @Test
    void nonexistentCustomerDoesNotConsumeNumberOrPersistAccount() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.existsById(customerId)).thenReturn(false);
        AccountService service = new AccountService(accountRepository, customerRepository, numberGenerator);

        assertThatThrownBy(() -> service.create(customerId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Customer not found");

        verify(numberGenerator, never()).nextNumber();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void missingAccountThrowsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());
        AccountService service = new AccountService(accountRepository, customerRepository, numberGenerator);

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account not found");
    }
}
