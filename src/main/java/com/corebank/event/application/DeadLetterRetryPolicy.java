package com.corebank.event.application;

import com.corebank.event.infrastructure.DeadLetterProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DeadLetterRetryPolicy {

    private final DeadLetterProperties properties;

    public DeadLetterRetryPolicy(DeadLetterProperties properties) {
        this.properties = properties;
    }

    public Duration delayAfterFailure(int publishAttempts) {
        if (publishAttempts < 1) {
            throw new IllegalArgumentException("publishAttempts must be at least 1");
        }
        int index = Math.min(publishAttempts - 1, properties.retryDelays().size() - 1);
        Duration configured = properties.retryDelays().get(index);
        return configured.compareTo(properties.maxDelay()) > 0 ? properties.maxDelay() : configured;
    }
}
