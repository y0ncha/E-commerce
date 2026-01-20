package mta.eda.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import mta.eda.consumer.exception.ConsumerDeserializationErrorHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:order-service-group}")
    private String groupId;

    @Value("${spring.kafka.consumer.key-deserializer}")
    private String keyDeserializer;

    @Value("${spring.kafka.consumer.value-deserializer}")
    private String valueDeserializer;

    @Value("${spring.kafka.consumer.auto-offset-reset}")
    private String autoOffsetReset;

    @Value("${spring.kafka.consumer.enable-auto-commit}")
    private String enableAutoCommit;

    /**
     * ConsumerFactory: Configures the Kafka consumer with proper deserialization and settings.
     * Key Settings:
     * - ENABLE_AUTO_COMMIT_CONFIG=false: At-Least-Once delivery requires disabling auto-commits
     *   to allow the application to explicitly acknowledge offsets only after successful processing.
     *   This prevents committed offsets from advancing beyond processed messages, ensuring no loss.
     * - AUTO_OFFSET_RESET_CONFIG=earliest: When no offset is found, start from the beginning of the topic.
     *   This ensures the consumer doesn't miss messages during startup or after crashes.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();

        // Bootstrap servers
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // Deserializers from application.properties
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer);

        // Offsets & Commits
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Configures the Kafka listener container factory:
     * - Manual immediate ack mode for precise offset control
     * - Custom error handler for routing failures to DLT
     * - Auto-startup enabled
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            ConsumerDeserializationErrorHandler deserializationErrorHandler) {

        // Container factory
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Ack mode: manual immediate (explicit commit after processing)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Error handler: route all failures to DLT
        factory.setCommonErrorHandler(deserializationErrorHandler);

        // Auto-startup enabled
        factory.setAutoStartup(true);

        return factory;
    }

    /**
     * ObjectMapper Bean: Provides JSON serialization/deserialization capability.
     * This bean is required by KafkaConsumerService for converting JSON messages to Order objects.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
