package com.corebank.event.infrastructure;

import com.corebank.event.application.DeadLetterService;
import com.corebank.event.application.PublicationFailureClassifier;
import com.corebank.event.application.RetryPolicy;
import com.corebank.event.domain.DeadLetterEvent;
import com.corebank.event.domain.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Service
public class OutboxPublicationWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublicationWorker.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final PublicationFailureClassifier failureClassifier;
    private final DeadLetterService deadLetterService;
    private final String topic;

    public OutboxPublicationWorker(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            RetryPolicy retryPolicy,
            PublicationFailureClassifier failureClassifier,
            DeadLetterService deadLetterService,
            @Value("${corebank.kafka.topic.financial-events:corebank.financial-events.v1}") String topic) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.failureClassifier = failureClassifier;
        this.deadLetterService = deadLetterService;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishNext() {
        Optional<OutboxEvent> candidate = repository.findNextPendingForUpdate();
        if (candidate.isEmpty()) {
            return false;
        }

        OutboxEvent event = candidate.orElseThrow();
        try {
            String json = objectMapper.writeValueAsString(event.toEnvelope());
            kafkaTemplate.send(topic, event.getMessageKey(), json).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handlePublicationFailure(event, ex);
            return true;
        } catch (JsonProcessingException | ExecutionException | RuntimeException ex) {
            handlePublicationFailure(event, ex);
            return true;
        }

        event.markPublished(Instant.now());
        repository.saveAndFlush(event);
        log.info(
                "Published outbox event eventId={} eventType={} aggregateId={} attemptCount={} status=PUBLISHED topic={}",
                event.getId(), event.getEventType(), event.getAggregateId(), event.getAttemptCount(), topic);
        return true;
    }

    private void handlePublicationFailure(OutboxEvent event, Throwable failure) {
        String errorType = failureClassifier.errorType(failure);
        String errorMessage = failureClassifier.sanitizedMessage(failure);
        int failedAttempt = event.getAttemptCount() + 1;

        if (!failureClassifier.isRetriable(failure) || failedAttempt >= retryPolicy.maxAttempts()) {
            event.markDeadLettered(errorType, errorMessage);
            repository.saveAndFlush(event);
            DeadLetterEvent deadLetter = deadLetterService.createFrom(event, errorType, errorMessage);
            log.warn(
                    "Dead-lettered outbox event eventId={} deadLetterId={} eventType={} aggregateId={} attemptCount={} status=DEAD_LETTERED",
                    event.getId(), deadLetter.getId(), event.getEventType(), event.getAggregateId(), event.getAttemptCount());
            return;
        }

        Instant nextAttemptAt = Instant.now().plus(retryPolicy.delayAfterFailure(failedAttempt));
        event.recordRetriableFailure(errorType, errorMessage, nextAttemptAt);
        repository.saveAndFlush(event);
        log.warn(
                "Scheduled outbox retry eventId={} eventType={} aggregateId={} attemptCount={} nextAttemptAt={} status=PENDING",
                event.getId(), event.getEventType(), event.getAggregateId(), event.getAttemptCount(), event.getNextAttemptAt());
    }
}
