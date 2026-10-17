package com.corebank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_transactions")
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(length = 200)
    private String description;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "counterparty_account_id")
    private UUID counterpartyAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountTransaction() {
    }

    public AccountTransaction(UUID accountId, TransactionType type, BigDecimal amount,
                              BigDecimal balanceBefore, BigDecimal balanceAfter, String description) {
        this(accountId, type, amount, balanceBefore, balanceAfter, description, null, null);
        if (type.isTransfer()) {
            throw new IllegalArgumentException("Transfer transactions require transfer metadata");
        }
    }

    private AccountTransaction(UUID accountId, TransactionType type, BigDecimal amount,
                               BigDecimal balanceBefore, BigDecimal balanceAfter, String description,
                               UUID transferId, UUID counterpartyAccountId) {
        this.accountId = require(accountId, "Account id is required");
        this.type = require(type, "Transaction type is required");
        this.amount = positiveMoney(amount, "Amount must be positive");
        this.balanceBefore = nonNegativeMoney(balanceBefore, "Balance before must be non-negative");
        this.balanceAfter = nonNegativeMoney(balanceAfter, "Balance after must be non-negative");
        this.description = normalizeDescription(description);
        this.transferId = transferId;
        this.counterpartyAccountId = counterpartyAccountId;
    }

    public static AccountTransaction transfer(UUID accountId, TransactionType type, BigDecimal amount,
                                               BigDecimal balanceBefore, BigDecimal balanceAfter, String description,
                                               UUID transferId, UUID counterpartyAccountId) {
        if (type == null || !type.isTransfer()) {
            throw new IllegalArgumentException("Transfer transaction type must be TRANSFER_OUT or TRANSFER_IN");
        }
        require(transferId, "Transfer id is required");
        require(counterpartyAccountId, "Counterparty account id is required");
        if (accountId != null && accountId.equals(counterpartyAccountId)) {
            throw new IllegalArgumentException("Counterparty account must be different");
        }
        return new AccountTransaction(accountId, type, amount, balanceBefore, balanceAfter, description,
                transferId, counterpartyAccountId);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    private static BigDecimal positiveMoney(BigDecimal value, String message) {
        BigDecimal normalized = nonNegativeMoney(value, message);
        if (normalized.signum() <= 0) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String message) {
        if (value == null || value.signum() < 0 || value.scale() > 2) throw new IllegalArgumentException(message);
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String normalizeDescription(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 200) throw new IllegalArgumentException("Description must have at most 200 characters");
        return trimmed;
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getDescription() { return description; }
    public UUID getTransferId() { return transferId; }
    public UUID getCounterpartyAccountId() { return counterpartyAccountId; }
    public Instant getCreatedAt() { return createdAt; }
}
