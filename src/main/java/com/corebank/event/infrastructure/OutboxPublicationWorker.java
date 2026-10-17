package com.corebank.event.infrastructure;

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
    private final String topic;

    public OutboxPublicationWorker(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${corebank.kafka.topic.financial-events:corebank.financial-events.v1}") String topic) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
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
            event.markPublished(Instant.now());
            repository.saveAndFlush(event);
            log.info(
                    "Published outbox event eventId={} eventType={} aggregateId={} status=PUBLISHED topic={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), topic);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Outbox publish interrupted eventId={} eventType={} aggregateId={} status=PENDING topic={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), topic);
            throw new IllegalStateException("Interrupted while publishing outbox event", ex);
        } catch (JsonProcessingException | ExecutionException ex) {
            log.warn(
                    "Outbox publish failed eventId={} eventType={} aggregateId={} status=PENDING topic={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), topic);
            throw new IllegalStateException("Could not publish outbox event", ex);
        }
    }
}
