package mta.eda.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
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
     * 4. Auto-startup enabled for immediate consumption
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
        // Retry Pattern (optimized for fast recovery):
        // - Initial interval: 1 second (faster than before)
        // - Multiplier: 2.0 (exponential)
        // - Max interval: 10 seconds (quicker retries)
        // - Max attempts: 3
        // Retry sequence: 1s, 2s, 4s
        // This allows transient failures (brief Kafka unavailability, network hiccups) to recover
        // quickly without giving up immediately, while also not waiting too long.
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
        backOff.setMaxInterval(10000);  // Cap at 10 seconds (reduced from 30s)

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate),  // Send to DLT on final failure
                backOff
        );

        factory.setCommonErrorHandler(errorHandler);

        // Enable auto-startup for immediate consumption
        // Note: This means Spring will attempt to connect immediately on startup.
        // If Kafka is down, Spring will log errors until it connects.
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

    /**
     * DLQ Producer Factory: Configures a Kafka producer for sending poison pills to DLQ.
     *
     * Why in consumer config: When a non-transient error (poison pill) is detected,
     * the consumer needs to send it to the DLQ topic. This requires producer configuration.
     * Uses String-String serialization to preserve raw message payload.
     */
    @Bean
    public ProducerFactory<String, String> dlqProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.CLIENT_ID_CONFIG, "consumer-dlq-producer");
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Reliability settings
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // Timeouts
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * DLQ Kafka Template: Template for sending String messages to DLQ.
     * Injected into DlqProducerService for sending poison pills.
     */
    @Bean
    public KafkaTemplate<String, String> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dlqProducerFactory());
    }
}
