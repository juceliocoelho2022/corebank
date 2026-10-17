package com.corebank.event.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxPublicationWorker worker;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxPublicationWorker worker, OutboxProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    public int publishPendingBatch() {
        int published = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            try {
                if (!worker.publishNext()) {
                    break;
                }
                published++;
            } catch (RuntimeException ex) {
                log.warn("Stopping current outbox batch after publication failure");
                break;
            }
        }
        return published;
    }
}
