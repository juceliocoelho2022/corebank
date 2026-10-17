package com.corebank.event.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicConfigTest {

    private final KafkaTopicConfig config = new KafkaTopicConfig(
            "corebank.financial-events.v1",
            "corebank.financial-events.dlq.v1");

    @Test
    void financialEventsTopic_shouldUseApprovedTopology() {
        NewTopic topic = config.financialEventsTopic();

        assertThat(topic.name()).isEqualTo("corebank.financial-events.v1");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void deadLetterTopic_shouldUseApprovedTopology() {
        NewTopic topic = config.financialEventsDlqTopic();

        assertThat(topic.name()).isEqualTo("corebank.financial-events.dlq.v1");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
