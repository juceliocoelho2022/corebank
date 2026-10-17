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
        if (publishAttempts > properties.retryDelays().size()) {
            return properties.maxDelay();
        }
        Duration configured = properties.retryDelays().get(publishAttempts - 1);
        return configured.compareTo(properties.maxDelay()) > 0
                ? properties.maxDelay()
                : configured;
    }
}
