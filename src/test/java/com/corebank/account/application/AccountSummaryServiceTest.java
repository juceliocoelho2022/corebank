package com.corebank.account.application;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountSummaryServiceTest {
    @Test
    void mapsOnlyBalanceFreeMetadata() {
        AccountRepository repository = mock(AccountRepository.class);
        Account account = mock(Account.class);
        UUID id = UUID.randomUUID(), customerId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-17T12:00:00Z");
        when(account.getId()).thenReturn(id);
        when(account.getCustomerId()).thenReturn(customerId);
        when(account.getBranch()).thenReturn("0001");
        when(account.getNumber()).thenReturn("10000001");
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getCurrency()).thenReturn("BRL");
        when(account.getCreatedAt()).thenReturn(at);
        when(repository.findById(id)).thenReturn(Optional.of(account));
        AccountSummary summary = new AccountSummaryService(repository).get(id);
        assertThat(summary).isEqualTo(new AccountSummary(id, customerId, "0001", "10000001",
                AccountStatus.ACTIVE, "BRL", at));
        assertThat(AccountSummary.class.getRecordComponents()).extracting("name")
                .containsExactly("id", "customerId", "branch", "number", "status", "currency", "createdAt");
        verify(account, never()).getBalance();
    }

    @Test
    void missingAccountRemainsNotFound() {
        AccountRepository repository = mock(AccountRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new AccountSummaryService(repository).get(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
