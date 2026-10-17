package com.corebank.event.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterScheduler {

    private final DeadLetterPublisher publisher;

    public DeadLetterScheduler(DeadLetterPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${corebank.dlq.fixed-delay-ms:2000}",
            initialDelayString = "${corebank.dlq.fixed-delay-ms:2000}")
    public void publishPending() {
        publisher.publishPendingBatch();
    }
}
