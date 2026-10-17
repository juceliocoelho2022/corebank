package com.corebank.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LedgerAccountType type;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {
    }

    public LedgerAccount(String code, LedgerAccountType type, UUID accountId, String currency) {
        this.code = requireText(code, "code");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.currency = requireText(currency, "currency");

        if (!"BRL".equals(this.currency)) {
            throw new IllegalArgumentException("Ledger account currency must be BRL");
        }
        if (type == LedgerAccountType.CUSTOMER_LIABILITY && accountId == null) {
            throw new IllegalArgumentException("CUSTOMER_LIABILITY requires accountId");
        }
        if (type == LedgerAccountType.CASH_CLEARING && accountId != null) {
            throw new IllegalArgumentException("CASH_CLEARING must not have accountId");
        }

        this.accountId = accountId;
    }

    public static LedgerAccount customerLiability(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        return new LedgerAccount("CUSTOMER:" + accountId, LedgerAccountType.CUSTOMER_LIABILITY, accountId, "BRL");
    }

    public static LedgerAccount cashClearing() {
        return new LedgerAccount("TECH:CASH_CLEARING", LedgerAccountType.CASH_CLEARING, null, "BRL");
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public LedgerAccountType getType() {
        return type;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
