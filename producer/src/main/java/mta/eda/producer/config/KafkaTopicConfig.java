package mta.eda.producer.config;

import org.apache.kafka.clients.admin.NewTopic;
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
}
