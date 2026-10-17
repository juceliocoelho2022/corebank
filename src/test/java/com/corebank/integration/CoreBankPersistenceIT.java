package com.corebank.integration;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CoreBankPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        accountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void flywayCreatesCustomersAndAccountsTables() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('customers', 'accounts')
                """, Integer.class);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void persistsCustomer() {
        Customer saved = customerRepository.saveAndFlush(
                new Customer("Ana Silva", "12345678901", "ana@example.com"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(customerRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void rejectsDuplicateDocument() {
        customerRepository.saveAndFlush(new Customer("Ana Silva", "12345678901", "ana@example.com"));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(
                new Customer("Bruno Souza", "12345678901", "bruno@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateEmailIgnoringCaseAtDatabaseLevel() {
        customerRepository.saveAndFlush(new Customer("Ana Silva", "12345678901", "Ana@Example.com"));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(
                new Customer("Bruno Souza", "10987654321", "ana@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test
    void persistsAccountWithDefaultsAndVersion() {
        Customer customer = customerRepository.saveAndFlush(
                new Customer("Ana Silva", "12345678901", "ana@example.com"));

        Account account = accountRepository.saveAndFlush(new Account(customer.getId(), "10009991"));

        assertThat(account.getId()).isNotNull();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(account.getBalance().scale()).isEqualTo(2);
        assertThat(account.getVersion()).isNotNull();
    }

    @Test
    void rejectsAccountForUnknownCustomer() {
        assertThatThrownBy(() -> accountRepository.saveAndFlush(
                new Account(UUID.randomUUID(), "10009992")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateAccountNumber() {
        Customer customer = customerRepository.saveAndFlush(
                new Customer("Ana Silva", "12345678901", "ana@example.com"));
        accountRepository.saveAndFlush(new Account(customer.getId(), "10009993"));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(
                new Account(customer.getId(), "10009993")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

}
