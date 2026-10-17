package com.corebank.integration;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import com.corebank.transfer.application.TransferResult;
import com.corebank.transfer.application.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired TransferService transferService;
    @MockitoSpyBean AccountTransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer sourceCustomer = customerRepository.saveAndFlush(new Customer(
                "Ana Silva", UUID.randomUUID().toString(), "ana." + UUID.randomUUID() + "@example.com"));
        Customer destinationCustomer = customerRepository.saveAndFlush(new Customer(
                "Bruno Souza", UUID.randomUUID().toString(), "bruno." + UUID.randomUUID() + "@example.com"));

        Account source = new Account(sourceCustomer.getId(), "10007771");
        source.deposit(new BigDecimal("100.00"));
        sourceId = accountRepository.saveAndFlush(source).getId();
        destinationId = accountRepository.saveAndFlush(new Account(destinationCustomer.getId(), "10007772")).getId();
    }

    @Test
    void transfer_shouldCommitBothBalancesAndExactlyTwoCorrelatedEntries() {
        TransferResult result = transferService.transfer(sourceId, destinationId, new BigDecimal("30.00"), "Rent");

        assertThat(accountRepository.findById(sourceId).orElseThrow().getBalance()).isEqualByComparingTo("70.00");
        assertThat(accountRepository.findById(destinationId).orElseThrow().getBalance()).isEqualByComparingTo("30.00");

        List<AccountTransaction> sourceHistory = transactionRepository.findByAccountIdOrderByCreatedAtDesc(sourceId);
        List<AccountTransaction> destinationHistory = transactionRepository.findByAccountIdOrderByCreatedAtDesc(destinationId);
        assertThat(sourceHistory).hasSize(1);
        assertThat(destinationHistory).hasSize(1);

        AccountTransaction out = sourceHistory.get(0);
        AccountTransaction in = destinationHistory.get(0);
        assertThat(out.getType()).isEqualTo(TransactionType.TRANSFER_OUT);
        assertThat(in.getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(out.getTransferId()).isEqualTo(result.transferId());
        assertThat(in.getTransferId()).isEqualTo(result.transferId());
        assertThat(out.getCounterpartyAccountId()).isEqualTo(destinationId);
        assertThat(in.getCounterpartyAccountId()).isEqualTo(sourceId);
    }

    @Test
    void historyFailure_shouldRollbackBothBalancesAndAllHistory() {
        doThrow(new DataIntegrityViolationException("forced history failure"))
                .when(transactionRepository).save(any(AccountTransaction.class));

        assertThatThrownBy(() -> transferService.transfer(sourceId, destinationId, new BigDecimal("30.00"), null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(accountRepository.findById(sourceId).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(accountRepository.findById(destinationId).orElseThrow().getBalance()).isEqualByComparingTo("0.00");
        assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(sourceId)).isEmpty();
        assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(destinationId)).isEmpty();
    }
}
