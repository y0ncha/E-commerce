package mta.eda.consumer.exception;

import mta.eda.consumer.service.kafka.DltProducerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * ConsumerDeserializationErrorHandler: Handles message deserialization failures
 * by routing them to the Dead Letter Topic (orders-dlt).
 * Deserialization errors occur when:
 * - Kafka message value fails to deserialize (malformed JSON, corrupt payload)
 * - This happens at the Spring Kafka listener layer, before the listener method is invoked
 * Strategy:
 * - Extract available metadata (topic, partition, offset, key) from the consumer record
 * - Send the raw message payload to orders-dlt with error context headers
 * - Do NOT retry: malformed data won't become valid on retry (uses 0 retries)
 * Course Alignment:
 * - Implements resilient error handling for data quality issues
 * - Preserves message traceability with headers and metadata
 * - Uses async DLT producer for non-blocking error handling
 */
@Component
public class ConsumerDeserializationErrorHandler extends DefaultErrorHandler {

    public ConsumerDeserializationErrorHandler(DltProducerService dltProducerService) {
        // Use FixedBackOff with 0 retries - no retries for malformed data
        super((record, exception) -> {
            // This is the recoverer function - routes to DLT
            sendToDltStatic(record, exception, dltProducerService);
        }, new FixedBackOff(0L, 0L));
    }

    /**
     * Static helper method to send failed records to DLT.
     * Used by the recoverer lambda.
     *
     * @param record the failed consumer record
     * @param exception the exception that occurred
     * @param dltService the DLT producer service
     */
    private static void sendToDltStatic(ConsumerRecord<?, ?> record, Exception exception, DltProducerService dltService) {
        String topic = record.topic();
        int partition = record.partition();
        long offset = record.offset();
        Object rawKey = record.key();
        Object rawValue = record.value();

        // Extract key as string (orderId for traceability)
        String key = rawKey != null ? rawKey.toString() : "unknown";

        // Extract value as string (raw payload for DLT storage)
        String value = rawValue != null ? rawValue.toString() : "";

        String errorReason = "Message processing failed: " + exception.getClass().getSimpleName() +
                " - " + exception.getMessage();

        Logger logger = LoggerFactory.getLogger(ConsumerDeserializationErrorHandler.class);
        logger.error("Error processing message. Topic={}, Partition={}, Offset={}, Key={}, " +
                "Exception={}, Message={}",
                topic, partition, offset, key, exception.getClass().getSimpleName(),
                exception.getMessage(), exception);

        try {
            // Send to DLT with metadata headers for analysis
            dltService.sendToDlt(topic, key, value, errorReason, partition, offset);

            logger.info("✓ Error message routed to DLT. Topic={}, Partition={}, " +
                    "Offset={}, Key={}", topic, partition, offset, key);

        } catch (Exception e) {
            // If DLT send fails, log critical error but don't fail the consumer
            logger.error("CRITICAL: Failed to send error message to DLT. " +
                    "Topic={}, Partition={}, Offset={}, Key={}, Error={}",
                    topic, partition, offset, key, e.getMessage(), e);
        }
    }
}

