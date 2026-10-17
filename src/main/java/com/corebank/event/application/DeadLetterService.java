package com.corebank.event.application;

import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.infrastructure.DeadLetterEventRepository;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterService {

    private final DeadLetterEventRepository repository;

    public DeadLetterService(DeadLetterEventRepository repository) {
        this.repository = repository;
    }

    public DeadLetterEvent createFrom(OutboxEvent event, String errorType, String errorMessage) {
        return repository.findByOriginalEventId(event.getId())
                .orElseGet(() -> repository.saveAndFlush(
                        DeadLetterEvent.from(event, errorType, errorMessage)));
    }
}
