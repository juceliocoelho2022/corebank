package com.corebank.ledger.infrastructure;

import com.corebank.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query(value = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN side = 'CREDIT' THEN amount
                    WHEN side = 'DEBIT' THEN -amount
                    ELSE 0
                END
            ), 0.00)
            FROM ledger_entries
            WHERE ledger_account_id = :ledgerAccountId
            """, nativeQuery = true)
    BigDecimal calculateLiabilityBalance(@Param("ledgerAccountId") UUID ledgerAccountId);
}
