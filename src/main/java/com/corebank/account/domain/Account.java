package com.corebank.account.domain;

import jakarta.persistence.*;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAccountStateException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    public static final String DEFAULT_BRANCH = "0001";
    public static final String DEFAULT_CURRENCY = "BRL";
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "customer_id", nullable = false) private UUID customerId;
    @Column(nullable = false, length = 4) private String branch;
    @Column(nullable = false, unique = true, length = 8) private String number;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AccountStatus status;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal balance;
    @Column(nullable = false, length = 3) private String currency;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Account() {}
    public Account(UUID customerId, String number) {
        this.customerId = customerId; this.branch = DEFAULT_BRANCH; this.number = number;
        this.status = AccountStatus.ACTIVE; this.balance = money(BigDecimal.ZERO); this.currency = DEFAULT_CURRENCY;
    }
    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("Balance must be non-negative");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
    public BigDecimal deposit(BigDecimal amount) {
        BigDecimal normalized = movementAmount(amount); ensureActive(); BigDecimal before = balance;
        balance = money(balance.add(normalized)); return before;
    }
    public BigDecimal withdraw(BigDecimal amount) {
        BigDecimal normalized = movementAmount(amount); ensureActive();
        if (balance.compareTo(normalized) < 0) throw new InsufficientBalanceException();
        BigDecimal before = balance; balance = money(balance.subtract(normalized)); return before;
    }
    private BigDecimal movementAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) throw new IllegalArgumentException("Amount must be positive and have at most two decimal places");
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
    private void ensureActive() { if (status != AccountStatus.ACTIVE) throw new InvalidAccountStateException(status.name()); }
    @PrePersist void onCreate() { balance = balance == null ? money(BigDecimal.ZERO) : money(balance); Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { balance = money(balance); updatedAt = Instant.now(); }
    public UUID getId(){return id;} public UUID getCustomerId(){return customerId;} public String getBranch(){return branch;} public String getNumber(){return number;}
    public AccountStatus getStatus(){return status;} public BigDecimal getBalance(){return balance;} public String getCurrency(){return currency;}
    public Long getVersion(){return version;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
