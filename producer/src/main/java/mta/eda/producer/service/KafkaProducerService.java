package mta.eda.producer.service;

import mta.eda.producer.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * KafkaProducerService
 * Handles publishing Order messages to Kafka.
 * CRITICAL: Uses orderId as the message key to ensure ordering per order.
 */
@Service
public class KafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, Order> kafkaTemplate;

    @Value("${kafka.topic.name}")
    private String topicName;

    public KafkaProducerService(KafkaTemplate<String, Order> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends an Order to Kafka.
     *
     * @param orderId The order ID (used as Kafka message key for ordering)
     * @param order The order to publish
     * @throws RuntimeException if send fails
     */
    public void sendOrder(String orderId, Order order) {
        logger.info("Attempting to send order for orderId={} with status={}", orderId, order.status());

        // Send message with orderId as key (CRITICAL for ordering guarantee)
        CompletableFuture<SendResult<String, Order>> future =
            kafkaTemplate.send(topicName, orderId, order);

        // Handle async response
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                logger.info("Successfully sent order for orderId={} to partition={} with offset={}",
                        orderId,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                logger.error("Failed to send order for orderId={}: {}", orderId, ex.getMessage(), ex);
                throw new RuntimeException("Failed to send message to Kafka: " + ex.getMessage(), ex);
            }
        });
    }
}