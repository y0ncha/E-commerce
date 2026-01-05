package mta.eda.consumer.service.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.service.order.OrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * KafkaConsumerService: Resilient Kafka consumer with At-Least-Once delivery semantics.
 * Responsibilities:
 * 1. Listen to orders topic with Kafka listener
 * 2. Deserialize JSON messages to Order objects
 * 3. Implement idempotency check to detect duplicate deliveries
 * 4. Maintain strict message sequencing using orderId as partition key
 * 5. Manual acknowledgment only after successful processing
 * 6. Error propagation to ConsumerDeserializationErrorHandler for DLT routing
 * At-Least-Once Delivery Model:
 * - Kafka is configured with manual offset management (enable-auto-commit=false)
 * - Offsets are NOT committed until after business logic succeeds
 * - If the listener throws an exception, offset remains uncommitted
 * - The ConsumerDeserializationErrorHandler catches exceptions and routes to DLT
 * - Failed messages are sent to the Dead Letter Topic (orders-dlt)
 * - The listener never calls acknowledgment.acknowledge() for failed messages
 * Sequencing & Idempotency:
 * - orderId is used as the Kafka message key
 * - Kafka guarantees messages with the same key go to the same partition
 * - Partitions guarantee message ordering within a partition
 * - Therefore, messages for the same orderId are always processed in order
 * - The idempotencyMap tracks processed message offsets to detect retries
 * - If a message has already been processed (detected by orderId in state), it's skipped
 * Error Propagation:
 * - If JSON deserialization fails, throw RuntimeException
 * - If business logic throws an exception, let it propagate (don't catch)
 * - The ConsumerDeserializationErrorHandler catches these exceptions
 * - Error handler sends failed messages to orders-dlt with full metadata
 * - Only successful messages get manually acknowledged
 */
@Service
public class KafkaConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerService.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    // Idempotency tracking: maps orderId to the last processed message details
    // Used to detect duplicate deliveries when a message is retried
    private final ConcurrentMap<String, ProcessedMessageInfo> idempotencyMap = new ConcurrentHashMap<>();

    public KafkaConsumerService(
            @Autowired OrderService orderService,
            @Autowired ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    /**
     * Kafka listener for order messages with strict sequencing guarantees.
     * Uses ConsumerRecord to access both the message data and metadata (topic, partition, offset, key).
     * Historical Tracking:
     * - Extracts topicName from record.topic() to track which topic the message came from
     * - Saves topicName alongside order data for future querying
     * - This allows answering "which orders came from topic X?" queries
     * @param record the ConsumerRecord containing message payload, key, topic, partition, offset
     * @param acknowledgment the manual acknowledgment handler
     */
    @KafkaListener(
            topics = "${kafka.consumer.topic:orders}",
            groupId = "${spring.kafka.consumer.group-id:order-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrder(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment) {

        String orderId = record.key();
        String payload = record.value();
        long offset = record.offset();
        String topicName = record.topic();

        logger.info("Received order message: orderId={}, topic={}, partition={}, offset={}",
                orderId, topicName, record.partition(), offset);

        try {
            // STEP 1: Deserialize JSON to Order object
            // If deserialization fails, throw exception to trigger retry/DLT
            Order order = deserializeOrder(payload, topicName);

            // STEP 2: Idempotency check using orderId
            // Detect if we've already processed this message (duplicate delivery from retry)
            if (isMessageAlreadyProcessed(orderId, offset)) {
                logger.info("Idempotency check: Order {} at offset {} already processed. Skipping.", orderId, offset);
                // Acknowledge the offset even though we're skipping (at-least-once: message won't be redelivered)
                acknowledgment.acknowledge();
                return;
            }

            // STEP 3: Business logic - process the order
            // OrderService.processOrder() handles:
            // - Status transition validation (sequencing check)
            // - Duplicate detection (exact same status = duplicate)
            // - Shipping cost calculation
            // - State update with concurrent hashmap
            orderService.processOrder(order);

            // STEP 4: Mark message as processed for idempotency
            recordProcessedMessage(orderId, offset);

            // STEP 5: Manual acknowledgment - only after successful processing
            // This commits the offset immediately (ack-mode=manual_immediate)
            // Offset advancement ensures this message won't be redelivered
            acknowledgment.acknowledge();

            logger.info("✓ Successfully processed order: orderId={}, status={}, topic={}",
                    orderId, order.status(), topicName);

        } catch (IOException e) {
            // JSON deserialization failed - propagate exception
            // The native CommonErrorHandler will retry with exponential backoff
            logger.error("✗ Failed to deserialize order message from topic {}: {}", topicName, e.getMessage(), e);
            throw new RuntimeException("Order deserialization failed for orderId=" + orderId, e);

        } catch (Exception e) {
            // Business logic or unexpected error - propagate exception
            // The native CommonErrorHandler will retry with exponential backoff
            logger.error("✗ Failed to process order {} from topic {}: {}", orderId, topicName, e.getMessage(), e);
            throw new RuntimeException("Order processing failed for orderId=" + orderId, e);
        }
    }

    /**
     * Deserialize JSON string to Order object and attach topicName.
     * The topicName is extracted from the ConsumerRecord metadata (record.topic())
     * and saved with the order for historical tracking.
     * Historical Tracking:
     * - Storing topicName at deserialization time ensures every order knows its origin
     * - This enables future queries like "get all orders from topic X"
     * - The topicName is a read-only field set at the moment of consumption
     * - It is never updated, making it a reliable audit trail
     * Throws IOException if JSON is invalid.
     * @param payload the JSON string (without topicName field)
     * @param topicName the Kafka topic name to attach to the order
     * @return the deserialized Order object with topicName included
     * @throws IOException if JSON parsing fails
     */
    private Order deserializeOrder(String payload, String topicName) throws IOException {
        // Deserialize the JSON payload
        Order orderWithoutTopic = objectMapper.readValue(payload, Order.class);

        // Create new Order with topicName attached
        // Since Order is a record, we reconstruct it with the topicName field
        return new Order(
                orderWithoutTopic.orderId(),
                orderWithoutTopic.customerId(),
                orderWithoutTopic.orderDate(),
                orderWithoutTopic.items(),
                orderWithoutTopic.totalAmount(),
                orderWithoutTopic.currency(),
                orderWithoutTopic.status(),
                topicName  // Attach the topic name from ConsumerRecord
        );
    }

    /**
     * Check if a message has already been processed (idempotency check).
     * The idempotencyMap stores the last seen offset for each orderId.
     * If we receive a message with the same orderId and equal or earlier offset,
     * it indicates a retry of an already-processed message.
     * Why this works for At-Least-Once:
     * - When a message is successfully processed, its offset is committed
     * - On redelivery (after failure), the message comes with the same offset
     * - We detect this and skip processing, preventing duplicate state updates
     * - We still acknowledge to prevent infinite redelivery attempts
     * Note: This assumes that for the same orderId, Kafka only redelivers
     * the exact same message (with the same offset) in case of failure.
     * This is guaranteed because the same orderId always goes to the same partition.
     *
     * @param orderId the order ID (message key)
     * @param offset the offset in the partition
     * @return true if this message has already been processed
     */
    private boolean isMessageAlreadyProcessed(String orderId, long offset) {
        ProcessedMessageInfo existing = idempotencyMap.get(orderId);
        if (existing == null) {
            // First time seeing this orderId
            return false;
        }

        // Compare offsets: if new offset <= last processed offset, it's a retry
        // (Kafka might redelivery the same message with same offset)
        return offset <= existing.offset;
    }

    /**
     * Record that a message has been successfully processed.
     *
     * @param orderId the order ID (message key)
     * @param offset the offset in the partition
     */
    private void recordProcessedMessage(String orderId, long offset) {
        idempotencyMap.put(orderId, new ProcessedMessageInfo(offset, System.currentTimeMillis()));
        logger.debug("Recorded processed message: orderId={}, offset={}", orderId, offset);
    }

    /**
     * Inner class to track processed message metadata for idempotency detection.
     */
    private record ProcessedMessageInfo(long offset, long processedAt) {
    }

    /**
     * Get the idempotency map size (for monitoring/debugging).
     * In production, you might want to periodically clean old entries.
     *
     * @return number of unique orders tracked
     */
    public int getTrackedOrderCount() {
        return idempotencyMap.size();
    }
}

