package mta.eda.producer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic Configuration
 * Creates topics at application startup via Spring Kafka's KafkaAdmin.
 * Topics are created synchronously during bean initialization.
 * Important: Topics MUST exist before sending messages. While fail-fast=false
 * allows the app to start if Kafka is down, attempting to send to a non-existent
 * topic will fail immediately without retries (as retries don't help for missing topics).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic(
            @Value("${kafka.topic.name}") String topicName,
            @Value("${kafka.topic.partitions:3}") int partitions,
            @Value("${kafka.topic.replication-factor:1}") short replicationFactor
    ) {
        return TopicBuilder.name(topicName)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    /**
     * Dead Letter Topic (DLT) for poison pills and deserialization failures.
     * Key Design Decisions:
     * 1. Same number of partitions as main topic (3) to preserve key-partition mapping
     * 2. This allows potential replay from DLT while maintaining ordering guarantees
     * 3. 7-day retention for manual intervention and analysis
     * 4. Messages sent here preserve the original orderId as the key
     */
    @Bean
    public NewTopic ordersDltTopic(
            @Value("${kafka.topic.partitions:3}") int partitions,
            @Value("${kafka.topic.replication-factor:1}") short replicationFactor
    ) {
        return TopicBuilder.name("orders-dlt")
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                .build();
    }
}

