package com.corebank.ledger.infrastructure;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.ledger.domain.LedgerAccount;
import com.corebank.ledger.domain.LedgerAccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LedgerPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired LedgerAccountRepository ledgerAccountRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ledger_accounts WHERE type = 'CUSTOMER_LIABILITY'");
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        Account account = accountRepository.saveAndFlush(new Account(customer.getId(), "10007771"));
        accountId = account.getId();
    }

    @Test
    void repository_shouldResolveCustomerLedgerAccountByAccountId() {
        LedgerAccount saved = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.customerLiability(accountId));

        assertThat(ledgerAccountRepository.findByAccountId(accountId))
                .contains(saved);
    }

    @Test
    void repository_shouldResolveCashClearingByCode() {
        LedgerAccount clearing = ledgerAccountRepository.findByCode("TECH:CASH_CLEARING")
                .orElseThrow();

        assertThat(clearing.getType()).isEqualTo(LedgerAccountType.CASH_CLEARING);
        assertThat(clearing.getAccountId()).isNull();
        assertThat(clearing.getCurrency()).isEqualTo("BRL");
    }
}
