package com.corebank.transaction.infrastructure;

import com.corebank.transaction.domain.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {
    List<AccountTransaction> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
