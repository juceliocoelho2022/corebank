package com.corebank.event.infrastructure;

import com.corebank.event.application.DeadLetterRetryPolicy;
import com.corebank.event.application.PublicationFailureClassifier;
import com.corebank.event.domain.DeadLetterEvent;
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
public class DeadLetterPublicationWorker {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublicationWorker.class);

    private final DeadLetterEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DeadLetterRetryPolicy retryPolicy;
    private final PublicationFailureClassifier failureClassifier;
    private final String topic;

    public DeadLetterPublicationWorker(
            DeadLetterEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            DeadLetterRetryPolicy retryPolicy,
            PublicationFailureClassifier failureClassifier,
            @Value("${corebank.kafka.topic.financial-events-dlq:corebank.financial-events.dlq.v1}") String topic) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.failureClassifier = failureClassifier;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishNext() {
        Optional<DeadLetterEvent> candidate = repository.findNextEligibleForUpdate();
        if (candidate.isEmpty()) {
            return false;
        }

        DeadLetterEvent event = candidate.orElseThrow();
        try {
            String json = objectMapper.writeValueAsString(event.toEnvelope());
            kafkaTemplate.send(topic, event.getMessageKey(), json).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handleFailure(event, ex);
            return true;
        } catch (JsonProcessingException | ExecutionException | RuntimeException ex) {
            handleFailure(event, ex);
            return true;
        }

        event.markPublished(Instant.now());
        repository.saveAndFlush(event);
        log.info(
                "Published dead-letter event deadLetterId={} originalEventId={} publishAttempts={} status=PUBLISHED topic={}",
                event.getId(), event.getOriginalEventId(), event.getPublishAttempts(), topic);
        return true;
    }

    private void handleFailure(DeadLetterEvent event, Throwable failure) {
        int nextPublishAttempt = event.getPublishAttempts() + 1;
        Instant nextAttemptAt = Instant.now().plus(retryPolicy.delayAfterFailure(nextPublishAttempt));
        event.recordPublishFailure(
                failureClassifier.errorType(failure),
                failureClassifier.sanitizedMessage(failure),
                nextAttemptAt);
        repository.saveAndFlush(event);
        log.warn(
                "Scheduled dead-letter retry deadLetterId={} originalEventId={} publishAttempts={} nextAttemptAt={} status=PENDING topic={}",
                event.getId(), event.getOriginalEventId(), event.getPublishAttempts(), event.getNextAttemptAt(), topic);
    }
}
