package com.corebank.event.application;

import com.corebank.event.infrastructure.OutboxProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RetryPolicy {

    private final OutboxProperties properties;

    public RetryPolicy(OutboxProperties properties) {
        this.properties = properties;
    }

    public Duration delayAfterFailure(int attemptCount) {
        if (attemptCount < 1 || attemptCount >= properties.maxAttempts()) {
            throw new IllegalArgumentException(
                    "attemptCount must be between 1 and " + (properties.maxAttempts() - 1));
        }
        return properties.retryDelays().get(attemptCount - 1);
    }

    public int maxAttempts() {
        return properties.maxAttempts();
    }
}
