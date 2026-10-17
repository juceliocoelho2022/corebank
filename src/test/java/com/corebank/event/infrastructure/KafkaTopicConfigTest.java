package com.corebank.event.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicConfigTest {

    @Test
    void financialEventsTopic_shouldUseApprovedTopology() {
        NewTopic topic = new KafkaTopicConfig("corebank.financial-events.v1").financialEventsTopic();

        assertThat(topic.name()).isEqualTo("corebank.financial-events.v1");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
