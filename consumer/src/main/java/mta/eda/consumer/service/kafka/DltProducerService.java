package mta.eda.consumer.service.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * DLQ Producer Service for Consumer
 * Handles sending poison pills to the Dead Letter Queue.
 * This service is called when:
 * 1. Message deserialization fails (JsonProcessingException)
 * 2. Message validation fails (IllegalArgumentException)
 * 3. Any non-transient error occurs that should not be retried
 * Design Requirements (MTA EDA Course):
 * - Preserve orderId as message key for sequencing and traceability
 * - Add metadata headers (original topic, error reason, timestamp)
 * - Use async callback to log DLQ producer success/failure
 * - CRITICAL: After sending to DLQ, caller MUST call acknowledgment.acknowledge()
 *   to commit the offset and prevent infinite retry loops
 */
@Service
public class DltProducerService {

    private static final Logger logger = LoggerFactory.getLogger(DltProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.dlt.topic.name:orders-dlt}")
    private String dltTopicName;

    public DltProducerService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends a poison pill message to the DLQ.
     *
     * @param originalTopic the topic where the message originally came from
     * @param key the message key (orderId) - MUST be preserved
     * @param value the original message payload (raw JSON string)
     * @param errorReason description of why the message failed
     * @param partition the original partition (for debugging)
     * @param offset the original offset (for debugging)
     */
    public void sendToDlt(String originalTopic, String key, String value,
                          String errorReason, int partition, long offset) {

        ProducerRecord<String, String> record = new ProducerRecord<>(dltTopicName, key, value);

        // Add metadata headers for debugging and traceability
        record.headers()
            .add("original-topic", originalTopic.getBytes(StandardCharsets.UTF_8))
            .add("original-partition", String.valueOf(partition).getBytes(StandardCharsets.UTF_8))
            .add("original-offset", String.valueOf(offset).getBytes(StandardCharsets.UTF_8))
            .add("error-reason", errorReason.getBytes(StandardCharsets.UTF_8))
            .add("failed-at", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));

        // Async send with callback (course requirement for resiliency)
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("CRITICAL: Failed to send message to DLT. Topic={}, Key={}, OriginalPartition={}, OriginalOffset={}, Error={}",
                           dltTopicName, key, partition, offset, ex.getMessage());
                // In production: trigger alert/monitoring system here
                // Consider: Should we fail the consumer if DLT send fails?
            } else {
                logger.warn("✓ Poison pill sent to DLT. Topic={}, Key={}, DLTPartition={}, DLTOffset={}, " +
                          "OriginalPartition={}, OriginalOffset={}, Reason={}",
                          dltTopicName, key, result.getRecordMetadata().partition(),
                          result.getRecordMetadata().offset(), partition, offset, errorReason);
            }
        });
    }
}

