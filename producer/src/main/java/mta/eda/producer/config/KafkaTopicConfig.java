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
     * Dead Letter Queue (DLQ) topic for poison pills.
     * Key Design Decisions:
     * 1. Same number of partitions as main topic (3) to preserve key-partition mapping
     * 2. This allows potential replay from DLQ while maintaining ordering guarantees
     * 3. 7-day retention for manual intervention and analysis
     * 4. Messages sent here preserve the original orderId as the key
     */
    @Bean
    public NewTopic ordersDlqTopic(
            @Value("${kafka.topic.partitions:3}") int partitions,
            @Value("${kafka.topic.replication-factor:1}") short replicationFactor
    ) {
        return TopicBuilder.name("orders-dlq")
                .partitions(partitions)  // Same as main topic for key-partition mapping preservation
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")  // 7 days retention
                .build();
    }
}
