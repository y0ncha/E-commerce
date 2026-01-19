package mta.eda.producer.config;

import mta.eda.producer.model.order.Order;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer Configuration
 * Creates the KafkaTemplate bean used by services to publish messages.
 * Configuration comes from application.properties but is programmatically
 * wired here to create Spring beans.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.key-serializer}")
    private String keySerializer;

    @Value("${spring.kafka.producer.value-serializer}")
    private String valueSerializer;

    @Value("${spring.kafka.producer.acks}")
    private String acks;

    @Value("${spring.kafka.producer.retries}")
    private int retries;

    @Value("${spring.kafka.producer.properties.enable.idempotence:false}")
    private boolean enableIdempotence;

    @Value("${spring.general.producer.client-id}")
    private String clientId;

    /**
     * Creates the ProducerFactory that configures how Kafka producers are created.
     * Key is String (orderId), Value is Order (JSON).
     */
    @Bean
    public ProducerFactory<String, Order> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // Bootstrap servers
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);


        // Serializers from application.properties
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);

        // Reliability settings
        configProps.put(ProducerConfig.ACKS_CONFIG, acks);
        configProps.put(ProducerConfig.RETRIES_CONFIG, retries);

        // Idempotence
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, enableIdempotence);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Creates the KafkaTemplate bean that services will inject and use to send messages.
     * This is the main API for publishing to Kafka.
     */
    @Bean
    public KafkaTemplate<String, Order> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
