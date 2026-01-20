package mta.eda.consumer.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.dlt.producer.client-id:orders-service}")
    private String dltClientId;
    @Value("${kafka.dlt.producer.acks:all}")
    private String dltAcks;
    @Value("${kafka.dlt.producer.idempotence:true}")
    private boolean dltIdempotence;
    @Value("${kafka.dlt.producer.retries:2147483647}")
    private int dltRetries;
    @Value("${kafka.dlt.producer.request-timeout-ms:10000}")
    private int dltRequestTimeoutMs;
    @Value("${kafka.dlt.producer.delivery-timeout-ms:15000}")
    private int dltDeliveryTimeoutMs;
    @Value("${kafka.dlt.producer.key-serializer:org.apache.kafka.common.serialization.StringSerializer}")
    private String dltKeySerializer;
    @Value("${kafka.dlt.producer.value-serializer:org.apache.kafka.common.serialization.StringSerializer}")
    private String dltValueSerializer;

    /**
     * DLQ Producer Factory: Configures a Kafka producer for sending poison pills to DLQ.
     * Used by DltProducerService to send failed messages to the DLQ topic.
     */
    @Bean
    public ProducerFactory<String, String> dltProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // Connection
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Identity
        configProps.put(ProducerConfig.CLIENT_ID_CONFIG, dltClientId);

        // Serialization
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, dltKeySerializer);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, dltValueSerializer);

        // Reliability
        configProps.put(ProducerConfig.ACKS_CONFIG, dltAcks);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, dltIdempotence);
        configProps.put(ProducerConfig.RETRIES_CONFIG, dltRetries);

        // Timeouts
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, dltRequestTimeoutMs);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, dltDeliveryTimeoutMs);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * DLQ Kafka Template: Template for sending String messages to DLQ.
     * Injected into DltProducerService for sending poison pills.
     */
    @Bean
    public KafkaTemplate<String, String> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dltProducerFactory());
    }
}
