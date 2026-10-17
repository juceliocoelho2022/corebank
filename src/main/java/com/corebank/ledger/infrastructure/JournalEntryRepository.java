package com.corebank.ledger.infrastructure;

import com.corebank.ledger.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
}
