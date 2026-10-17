package com.corebank.event.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties({OutboxProperties.class, DeadLetterProperties.class})
public class KafkaTopicConfig {

    private final String topicName;
    private final String dlqTopicName;

    public KafkaTopicConfig(
            @Value("${corebank.kafka.topic.financial-events:corebank.financial-events.v1}") String topicName,
            @Value("${corebank.kafka.topic.financial-events-dlq:corebank.financial-events.dlq.v1}") String dlqTopicName) {
        this.topicName = topicName;
        this.dlqTopicName = dlqTopicName;
    }

    @Bean
    public NewTopic financialEventsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic financialEventsDlqTopic() {
        return TopicBuilder.name(dlqTopicName)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
