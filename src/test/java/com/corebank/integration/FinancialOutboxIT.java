package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.domain.OutboxStatus;
import com.corebank.event.infrastructure.OutboxEventRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.transaction.application.TransactionService;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import com.corebank.transfer.application.TransferResult;
import com.corebank.transfer.application.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FinancialOutboxIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accountService;
    @Autowired CustomerRepository customerRepository;
    @Autowired TransactionService transactionService;
    @Autowired TransferService transferService;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired LedgerEntryRepository ledgerRepository;

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Outbox Customer",
                UUID.randomUUID().toString(),
                "outbox." + UUID.randomUUID() + "@example.com"));
        Account source = accountService.create(customer.getId());
        Account destination = accountService.create(customer.getId());
        sourceId = source.getId();
        destinationId = destination.getId();
    }

    @Test
    void deposit_shouldCreateExactlyOnePendingOutboxEvent() {
        long outboxBefore = outboxRepository.count();
        long txBefore = transactionRepository.count();
        long journalBefore = journalRepository.count();
        long ledgerBefore = ledgerRepository.count();

        var transaction = transactionService.deposit(sourceId, new BigDecimal("100.00"), "Salary");

        assertThat(outboxRepository.count()).isEqualTo(outboxBefore + 1);
        assertThat(transactionRepository.count()).isEqualTo(txBefore + 1);
        assertThat(journalRepository.count()).isEqualTo(journalBefore + 1);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerBefore + 2);

        OutboxEvent event = latestEvent();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getEventType()).isEqualTo(DomainEventType.DEPOSIT_COMPLETED);
        assertThat(event.getAggregateId()).isEqualTo(sourceId);
        assertThat(event.getCorrelationId()).isEqualTo(transaction.getId());
        assertThat(event.getMessageKey()).isEqualTo(sourceId.toString());
    }

    @Test
    void withdrawal_shouldCreateExactlyOnePendingOutboxEvent() {
        transactionService.deposit(sourceId, new BigDecimal("100.00"), "Funding");
        long outboxBefore = outboxRepository.count();

        var transaction = transactionService.withdraw(sourceId, new BigDecimal("25.00"), "Cash");

        assertThat(outboxRepository.count()).isEqualTo(outboxBefore + 1);
        OutboxEvent event = latestEvent();
        assertThat(event.getEventType()).isEqualTo(DomainEventType.WITHDRAWAL_COMPLETED);
        assertThat(event.getCorrelationId()).isEqualTo(transaction.getId());
        assertThat(event.getMessageKey()).isEqualTo(sourceId.toString());
    }

    @Test
    void transfer_shouldCreateOneTransferEventNotOnePerHistoryRow() {
        transactionService.deposit(sourceId, new BigDecimal("100.00"), "Funding");
        long outboxBefore = outboxRepository.count();
        long txBefore = transactionRepository.count();
        long journalBefore = journalRepository.count();
        long ledgerBefore = ledgerRepository.count();

        TransferResult transfer = transferService.transfer(
                sourceId, destinationId, new BigDecimal("30.00"), "Rent");

        assertThat(outboxRepository.count()).isEqualTo(outboxBefore + 1);
        assertThat(transactionRepository.count()).isEqualTo(txBefore + 2);
        assertThat(journalRepository.count()).isEqualTo(journalBefore + 1);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerBefore + 2);

        OutboxEvent event = latestEvent();
        assertThat(event.getEventType()).isEqualTo(DomainEventType.TRANSFER_COMPLETED);
        assertThat(event.getAggregateId()).isEqualTo(transfer.transferId());
        assertThat(event.getCorrelationId()).isEqualTo(transfer.transferId());
        assertThat(event.getMessageKey()).isEqualTo(sourceId.toString());
        assertThat(event.getPayload().path("destinationAccountId").asText())
                .isEqualTo(destinationId.toString());
    }

    private OutboxEvent latestEvent() {
        return outboxRepository.findAll().stream()
                .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                .orElseThrow();
    }
}
