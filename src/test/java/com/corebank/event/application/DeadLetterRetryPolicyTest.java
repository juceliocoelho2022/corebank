package com.corebank.event.application;

import com.corebank.event.infrastructure.DeadLetterProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetterRetryPolicyTest {

    private final DeadLetterRetryPolicy policy = new DeadLetterRetryPolicy(new DeadLetterProperties(
            100,
            2000,
            List.of(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60)),
            Duration.ofSeconds(60)));

    @Test
    void delayAfterFailure_shouldProgressAndCapAtSixtySeconds() {
        assertThat(policy.delayAfterFailure(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfterFailure(2)).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.delayAfterFailure(3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayAfterFailure(4)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayAfterFailure(5)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayAfterFailure(100)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void delayAfterFailure_shouldRejectNonPositiveAttempt() {
        assertThatThrownBy(() -> policy.delayAfterFailure(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
