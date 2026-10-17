package com.corebank.event.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationFailureClassifierTest {

    private final PublicationFailureClassifier classifier = new PublicationFailureClassifier();

    @Test
    void kafkaRetriableFailures_shouldBeRetriable() {
        assertThat(classifier.isRetriable(new TimeoutException("timeout"))).isTrue();
        assertThat(classifier.isRetriable(new NetworkException("network"))).isTrue();
        assertThat(classifier.isRetriable(new ExecutionException(new TimeoutException("timeout")))).isTrue();
    }

    @Test
    void interruptedFailure_shouldBeRetriable() {
        assertThat(classifier.isRetriable(new InterruptedException("interrupted"))).isTrue();
    }

    @Test
    void deterministicSerializationFailures_shouldNotBeRetriable() {
        JsonProcessingException jsonFailure = new JsonProcessingException("bad envelope") { };

        assertThat(classifier.isRetriable(jsonFailure)).isFalse();
        assertThat(classifier.isRetriable(new SerializationException("bad kafka serialization"))).isFalse();
    }

    @Test
    void unknownRuntimeFailure_shouldDefaultToNonRetriable() {
        assertThat(classifier.isRetriable(new IllegalStateException("unknown"))).isFalse();
    }

    @Test
    void errorMetadata_shouldBeStableAndSanitized() {
        Throwable failure = new ExecutionException(new TimeoutException("broker localhost:9092 unavailable\nstack-like-line"));

        assertThat(classifier.errorType(failure)).isEqualTo("TimeoutException");
        assertThat(classifier.sanitizedMessage(failure)).isEqualTo("Transient Kafka publication failure");
    }
}
