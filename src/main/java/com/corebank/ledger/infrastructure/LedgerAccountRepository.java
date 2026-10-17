package com.corebank.ledger.infrastructure;

import com.corebank.ledger.domain.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByAccountId(UUID accountId);
    Optional<LedgerAccount> findByCode(String code);
}
