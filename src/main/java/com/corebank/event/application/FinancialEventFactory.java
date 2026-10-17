package com.corebank.event.application;

import com.corebank.account.domain.Account;
import com.corebank.event.domain.DomainEventType;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.transaction.domain.AccountTransaction;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Component
public class FinancialEventFactory {

    public OutboxEvent depositCompleted(AccountTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return accountMovement(transaction, DomainEventType.DEPOSIT_COMPLETED);
    }

    public OutboxEvent withdrawalCompleted(AccountTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return accountMovement(transaction, DomainEventType.WITHDRAWAL_COMPLETED);
    }

    public OutboxEvent transferCompleted(
            UUID transferId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("transferId", transferId.toString())
                .put("sourceAccountId", sourceAccountId.toString())
                .put("destinationAccountId", destinationAccountId.toString())
                .put("amount", amount)
                .put("currency", Account.DEFAULT_CURRENCY);

        return OutboxEvent.pending(
                DomainEventType.TRANSFER_COMPLETED,
                1,
                "TRANSFER",
                transferId,
                transferId,
                sourceAccountId.toString(),
                payload);
    }

    private OutboxEvent accountMovement(AccountTransaction transaction, DomainEventType type) {
        UUID transactionId = Objects.requireNonNull(transaction.getId(), "transaction id must not be null");
        UUID accountId = Objects.requireNonNull(transaction.getAccountId(), "account id must not be null");

        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("accountId", accountId.toString())
                .put("transactionId", transactionId.toString())
                .put("amount", transaction.getAmount())
                .put("currency", Account.DEFAULT_CURRENCY);

        return OutboxEvent.pending(
                type,
                1,
                "ACCOUNT",
                accountId,
                transactionId,
                accountId.toString(),
                payload);
    }
}
