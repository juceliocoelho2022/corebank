package com.corebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    public static final String DEFAULT_BRANCH = "0001";
    public static final String DEFAULT_CURRENCY = "BRL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 4)
    private String branch;

    @Column(nullable = false, unique = true, length = 8)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public Account(UUID customerId, String number) {
        this.customerId = customerId;
        this.branch = DEFAULT_BRANCH;
        this.number = number;
        this.status = AccountStatus.ACTIVE;
        this.balance = money(BigDecimal.ZERO);
        this.currency = DEFAULT_CURRENCY;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Balance must be non-negative");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    @PrePersist
    void onCreate() {
        if (balance == null) {
            balance = money(BigDecimal.ZERO);
        } else {
            balance = money(balance);
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        balance = money(balance);
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getBranch() {
        return branch;
    }

    public String getNumber() {
        return number;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
