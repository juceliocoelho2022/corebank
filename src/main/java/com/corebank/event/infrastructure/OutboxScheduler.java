package com.corebank.event.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxScheduler {

    private final OutboxPublisher publisher;

    public OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${corebank.outbox.fixed-delay-ms:2000}",
            initialDelayString = "${corebank.outbox.fixed-delay-ms:2000}")
    public void publishPending() {
        publisher.publishPendingBatch();
    }
}
