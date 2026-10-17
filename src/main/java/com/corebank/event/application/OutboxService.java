package com.corebank.event.application;

import com.corebank.event.domain.OutboxEvent;
import com.corebank.event.infrastructure.OutboxEventRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class OutboxService {

    private final OutboxEventRepository repository;

    public OutboxService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    public OutboxEvent record(OutboxEvent event) {
        return repository.saveAndFlush(Objects.requireNonNull(event, "event must not be null"));
    }
}
