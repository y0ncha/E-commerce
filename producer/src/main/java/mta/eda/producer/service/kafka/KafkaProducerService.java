package mta.eda.producer.service.kafka;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import mta.eda.producer.exception.ProducerSendException;
import mta.eda.producer.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * KafkaProducerService
 * Publishes Order messages to Kafka.
 * Uses orderId as the Kafka key to guarantee ordering per order.
 */
@Service
public class KafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, Order> kafkaTemplate;
    private final Validator validator;

    @Value("${kafka.topic.name}")
    private String topicName;

    @Value("${producer.send.timeout.ms:10000}")
    private long sendTimeoutMs;

    public KafkaProducerService(KafkaTemplate<String, Order> kafkaTemplate, Validator validator) {
        this.kafkaTemplate = kafkaTemplate;
        this.validator = validator;
    }

    /**
     * Sends an Order to Kafka synchronously with bounded timeout.
     *
     * @param orderId The order ID (used as Kafka message key for ordering)
     * @param order   The order to publish
     * @throws ProducerSendException if send fails (classified by type)
     * @throws IllegalArgumentException if order validation fails
     */
    public void sendOrder(String orderId, Order order) {
        // Validate order before sending to Kafka
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            logger.error("Order validation failed for orderId={}: {}", orderId, errors);
            throw new IllegalArgumentException("Order validation failed: " + errors);
        }

        try {

            SendResult<String, Order> result =
                    kafkaTemplate.send(topicName, orderId, order)
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);

            logger.info("Successfully sent orderId={} partition={} offset={}",
                    orderId,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (TimeoutException e) {
            logger.error("Timeout sending orderId={} after {} ms", orderId, sendTimeoutMs, e);
            throw new ProducerSendException("TIMEOUT", orderId,
                    "Send timeout after " + sendTimeoutMs + "ms", e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while sending orderId={}", orderId, e);
            throw new ProducerSendException("INTERRUPTED", orderId,
                    "Send interrupted", e);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            logger.error("Kafka error sending orderId={}", orderId, cause);
            throw new ProducerSendException("KAFKA_ERROR", orderId,
                    "Kafka send failed: " + cause.getMessage(), cause);
        } catch (org.apache.kafka.common.KafkaException e) {
            logger.error("Kafka send failed for orderId={}. rootType={}, rootMsg={}",
                    orderId, e.getClass().getName(), e.getMessage(), e);
            throw new ProducerSendException("KAFKA_ERROR", orderId,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error sending orderId={}", orderId, e);
            throw new ProducerSendException("UNEXPECTED", orderId,
                    "Unexpected error: " + e.getMessage(), e);
        }
    }
}