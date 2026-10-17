package com.corebank.event.application;

import com.corebank.event.infrastructure.OutboxProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new OutboxProperties(
            100,
            2000,
            5,
            List.of(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30))));

    @Test
    void delayAfterFailure_shouldReturnConfiguredBackoff() {
        assertThat(policy.delayAfterFailure(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfterFailure(2)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfterFailure(3)).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.delayAfterFailure(4)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void delayAfterFailure_shouldRejectAttemptOutsideRetryWindow() {
        assertThatThrownBy(() -> policy.delayAfterFailure(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delayAfterFailure(5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
