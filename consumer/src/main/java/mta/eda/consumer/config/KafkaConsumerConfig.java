package mta.eda.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:order-service-group}")
    private String groupId;

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
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // MANDATORY: Disable auto-commit for At-Least-Once delivery semantics.
        // Manual acknowledgment allows precise control over when offsets are committed,
        // ensuring that offsets advance only after successful business logic processing.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * KafkaListenerContainerFactory: Configures the listener container with:
     * 1. Manual acknowledgment mode (AckMode.MANUAL_IMMEDIATE)
     * 2. Native error handling with exponential backoff retry
     * 3. Dead Letter Topic (DLT) recovery for persistently failed messages
     * 4. Disabled auto-startup to allow KafkaConnectivityService to manage startup
     * At-Least-Once Delivery Model:
     * - Messages are acknowledged ONLY after successful processing
     * - If processing fails, the offset is NOT committed, allowing redelivery
     * - Transient failures trigger exponential backoff retries
     * - Persistent failures are sent to the DLT for later analysis
     * Sequencing Guarantees:
     * - orderId is used as the message key, ensuring messages for the same order
     *   are routed to the same partition and processed in order
     * - The idempotency check in KafkaConsumerService detects duplicate deliveries
     *   using the orderId and skips already-processed messages
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // MANDATORY: Set AckMode to MANUAL_IMMEDIATE for precise offset control.
        // This allows the listener to explicitly call acknowledgment.acknowledge()
        // after successful processing, committing the offset immediately.
        // Failure to acknowledge prevents offset advancement, ensuring At-Least-Once semantics.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Configure native error handling with exponential backoff and DLT
        // Retry Pattern:
        // - Initial interval: 2 seconds
        // - Multiplier: 2.0 (exponential)
        // - Max attempts: 3
        // This allows transient failures (brief Kafka unavailability, network hiccups) to recover
        // without giving up immediately, while also not waiting indefinitely.
        ExponentialBackOff backOff = new ExponentialBackOff(2000, 2.0);
        backOff.setMaxInterval(30000);  // Cap at 30 seconds

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate),  // Send to DLT on final failure
                backOff
        );

        factory.setCommonErrorHandler(errorHandler);

        // Allow Consumer to run without Kafka - listeners won't auto-start if broker is unavailable
        // The KafkaConnectivityService will manage listener startup/shutdown based on broker availability
        factory.setAutoStartup(false);

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
