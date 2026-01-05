package mta.eda.producer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
     * Dead Letter Topic (DLT) for messages that fail after all retries.
     *
     * Topic Name: orders.DLT (Spring Kafka naming convention: {source-topic}.DLT)
     *
     * Key Design Decisions:
     * 1. Pre-created to ensure topic exists before first failure
     * 2. Same number of partitions as main topic (3) to preserve key-partition mapping
     * 3. This allows potential replay from DLT while maintaining ordering guarantees
     * 4. 7-day retention for manual intervention and analysis
     * 5. Messages sent here preserve the original orderId as the key
     *
     * Note: Spring Kafka's DeadLetterPublishingRecoverer automatically sends to {topic}.DLT,
     * so we pre-create this topic with appropriate settings rather than relying on auto-creation.
     */
    @Bean
    public NewTopic ordersDltTopic(
            @Value("${kafka.topic.partitions:3}") int partitions,
            @Value("${kafka.topic.replication-factor:1}") short replicationFactor
    ) {
        return TopicBuilder.name("orders.DLT")
                .partitions(partitions)  // Same as main topic for key-partition mapping preservation
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")  // 7 days retention
                .build();
    }
}
