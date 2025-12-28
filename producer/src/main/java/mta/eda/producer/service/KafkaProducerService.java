package mta.eda.producer.service;

import mta.eda.producer.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

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
        try {
            SendResult<String, Order> result =
                    kafkaTemplate.send(topicName, orderId, order).get();

            logger.info("Successfully sent orderId={} partition={} offset={}",
                    orderId,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (Exception e) {
            logger.error("Failed to send orderId={} to Kafka", orderId, e);
            throw new RuntimeException("Failed to send message to Kafka", e);
        }
    }
}