package com.corebank.event.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final DeadLetterPublicationWorker worker;
    private final DeadLetterProperties properties;

    public DeadLetterPublisher(DeadLetterPublicationWorker worker, DeadLetterProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    public int publishPendingBatch() {
        int handled = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            try {
                if (!worker.publishNext()) {
                    break;
                }
                handled++;
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Stopping current dead-letter batch because publisher thread was interrupted");
                    break;
                }
            } catch (RuntimeException ex) {
                log.warn("Stopping current dead-letter batch because publication state could not be persisted safely", ex);
                break;
            }
        }
        return handled;
    }
}
