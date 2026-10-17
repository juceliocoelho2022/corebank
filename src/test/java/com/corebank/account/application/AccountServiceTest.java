package com.corebank.account.application;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.ledger.domain.LedgerAccount;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
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

    @Mock AccountRepository accountRepository;
    @Mock CustomerRepository customerRepository;
    @Mock AccountNumberGenerator numberGenerator;
    @Mock LedgerAccountRepository ledgerAccountRepository;

    @Test
    void createsAccountWithBankingDefaultsAndCustomerLiabilityLedgerAccount() {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(customerRepository.existsById(customerId)).thenReturn(true);
        when(numberGenerator.nextNumber()).thenReturn("10000001");
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), accountId));
        when(ledgerAccountRepository.saveAndFlush(any(LedgerAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountService service = new AccountService(
                accountRepository, customerRepository, numberGenerator, ledgerAccountRepository);

        Account account = service.create(customerId);

        assertThat(account.getCustomerId()).isEqualTo(customerId);
        assertThat(account.getBranch()).isEqualTo("0001");
        assertThat(account.getNumber()).isEqualTo("10000001");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(account.getBalance().scale()).isEqualTo(2);
        assertThat(account.getCurrency()).isEqualTo("BRL");

        ArgumentCaptor<LedgerAccount> ledgerCaptor = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(ledgerAccountRepository).saveAndFlush(ledgerCaptor.capture());
        LedgerAccount ledgerAccount = ledgerCaptor.getValue();
        assertThat(ledgerAccount.getAccountId()).isEqualTo(accountId);
        assertThat(ledgerAccount.getCode()).isEqualTo("CUSTOMER:" + accountId);
        assertThat(ledgerAccount.getCurrency()).isEqualTo("BRL");
    }

    @Test
    void nonexistentCustomerDoesNotConsumeNumberOrPersistAccountOrLedgerAccount() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.existsById(customerId)).thenReturn(false);
        AccountService service = new AccountService(
                accountRepository, customerRepository, numberGenerator, ledgerAccountRepository);

        assertThatThrownBy(() -> service.create(customerId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Customer not found");

        verify(numberGenerator, never()).nextNumber();
        verify(accountRepository, never()).saveAndFlush(any());
        verify(ledgerAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingAccountThrowsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());
        AccountService service = new AccountService(
                accountRepository, customerRepository, numberGenerator, ledgerAccountRepository);

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account not found");
    }

    private static Account withId(Account account, UUID id) {
        try {
            Field field = Account.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(account, id);
            return account;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
