package com.corebank.event.infrastructure;

import com.corebank.event.domain.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    Optional<DeadLetterEvent> findByOriginalEventId(UUID originalEventId);

    @Query(value = """
            SELECT *
            FROM dead_letter_events
            WHERE status = 'PENDING'
              AND next_attempt_at <= NOW()
            ORDER BY next_attempt_at, created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<DeadLetterEvent> findNextEligibleForUpdate();
}
