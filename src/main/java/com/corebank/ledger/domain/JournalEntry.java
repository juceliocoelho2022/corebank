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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 32)
    private JournalReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JournalEntry() {
    }

    public JournalEntry(UUID referenceId, JournalReferenceType referenceType, String description) {
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId must not be null");
        this.referenceType = Objects.requireNonNull(referenceType, "referenceType must not be null");
        if (description != null && description.length() > 200) {
            throw new IllegalArgumentException("description must have at most 200 characters");
        }
        this.description = description;
    }

    public void validateBalanced(List<LedgerEntry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("Journal must contain at least two entries and be balanced");
        }

        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;

        for (LedgerEntry entry : entries) {
            Objects.requireNonNull(entry, "ledger entry must not be null");
            if (entry.getSide() == LedgerSide.DEBIT) {
                debit = debit.add(entry.getAmount());
            } else {
                credit = credit.add(entry.getAmount());
            }
        }

        if (debit.compareTo(credit) != 0) {
            throw new IllegalArgumentException("Journal entries must be balanced");
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public JournalReferenceType getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
