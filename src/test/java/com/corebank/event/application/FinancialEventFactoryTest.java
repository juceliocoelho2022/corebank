package com.corebank.event.application;

import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialEventFactoryTest {

    private final FinancialEventFactory factory = new FinancialEventFactory();

    @Test
    void depositCompleted_shouldBuildMinimalVersionedContract() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        AccountTransaction transaction = transaction(transactionId, accountId, TransactionType.DEPOSIT, "100.00", "Sensitive note");

        OutboxEvent event = factory.depositCompleted(transaction);

        assertThat(event.getEventType()).isEqualTo(DomainEventType.DEPOSIT_COMPLETED);
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(event.getAggregateId()).isEqualTo(accountId);
        assertThat(event.getCorrelationId()).isEqualTo(transactionId);
        assertThat(event.getMessageKey()).isEqualTo(accountId.toString());
        assertThat(event.getPayload().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accountId", "transactionId", "amount", "currency");
        assertThat(event.getPayload().path("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(event.getPayload().path("currency").asText()).isEqualTo("BRL");
        assertThat(event.getPayload().has("description")).isFalse();
        assertThat(event.getPayload().has("balance")).isFalse();
        assertThat(event.getPayload().has("idempotencyKey")).isFalse();
    }

    @Test
    void withdrawalCompleted_shouldUseAccountAndTransactionIdentifiers() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        AccountTransaction transaction = transaction(transactionId, accountId, TransactionType.WITHDRAWAL, "25.00", null);

        OutboxEvent event = factory.withdrawalCompleted(transaction);

        assertThat(event.getEventType()).isEqualTo(DomainEventType.WITHDRAWAL_COMPLETED);
        assertThat(event.getAggregateId()).isEqualTo(accountId);
        assertThat(event.getCorrelationId()).isEqualTo(transactionId);
        assertThat(event.getMessageKey()).isEqualTo(accountId.toString());
        assertThat(event.getPayload().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accountId", "transactionId", "amount", "currency");
    }

    @Test
    void transferCompleted_shouldBuildSingleTransferContractWithSourceKafkaKey() {
        UUID transferId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();

        OutboxEvent event = factory.transferCompleted(
                transferId, sourceId, destinationId, new BigDecimal("75.00"));

        assertThat(event.getEventType()).isEqualTo(DomainEventType.TRANSFER_COMPLETED);
        assertThat(event.getAggregateType()).isEqualTo("TRANSFER");
        assertThat(event.getAggregateId()).isEqualTo(transferId);
        assertThat(event.getCorrelationId()).isEqualTo(transferId);
        assertThat(event.getMessageKey()).isEqualTo(sourceId.toString());
        assertThat(event.getPayload().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("transferId", "sourceAccountId", "destinationAccountId", "amount", "currency");
        assertThat(event.getPayload().path("sourceAccountId").asText()).isEqualTo(sourceId.toString());
        assertThat(event.getPayload().path("destinationAccountId").asText()).isEqualTo(destinationId.toString());
        assertThat(event.getPayload().has("description")).isFalse();
    }

    private static AccountTransaction transaction(
            UUID transactionId,
            UUID accountId,
            TransactionType type,
            String amount,
            String description) {
        AccountTransaction transaction = mock(AccountTransaction.class);
        when(transaction.getId()).thenReturn(transactionId);
        when(transaction.getAccountId()).thenReturn(accountId);
        when(transaction.getType()).thenReturn(type);
        when(transaction.getAmount()).thenReturn(new BigDecimal(amount));
        when(transaction.getDescription()).thenReturn(description);
        return transaction;
    }
}
