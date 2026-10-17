package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.ledger.infrastructure.LedgerAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountLedgerCreationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired LedgerAccountRepository ledgerAccountRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID customerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_customer_ledger ON ledger_accounts");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_customer_ledger_insert()");
        jdbcTemplate.update("DELETE FROM ledger_accounts WHERE type = 'CUSTOMER_LIABILITY'");
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        customerId = customer.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_customer_ledger ON ledger_accounts");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_customer_ledger_insert()");
    }

    @Test
    void accountCreation_shouldAlsoCreateMatchingCustomerLiability() {
        Account account = accountService.create(customerId);

        assertThat(ledgerAccountRepository.findByAccountId(account.getId()))
                .hasValueSatisfying(ledger -> {
                    assertThat(ledger.getCode()).isEqualTo("CUSTOMER:" + account.getId());
                    assertThat(ledger.getCurrency()).isEqualTo("BRL");
                });
    }

    @Test
    void ledgerInsertFailure_shouldRollbackCoreBankAccount() {
        long accountsBefore = accountRepository.count();

        jdbcTemplate.execute("""
                CREATE FUNCTION fail_customer_ledger_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.type = 'CUSTOMER_LIABILITY' THEN
                        RAISE EXCEPTION 'forced ledger insert failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail_customer_ledger
                BEFORE INSERT ON ledger_accounts
                FOR EACH ROW
                EXECUTE FUNCTION fail_customer_ledger_insert()
                """);

        assertThatThrownBy(() -> accountService.create(customerId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
    }
}
