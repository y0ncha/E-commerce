package mta.eda.producer.service.kafka;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import mta.eda.producer.exception.ServiceUnavailableException;
import mta.eda.producer.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * KafkaProducerService
 * Implements a Synchronous Online Retry model.
 * Uses Kafka's internal retries and idempotence for maximum reliability.
 */
@Service
public class KafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);
    private static final Logger failedOrdersLogger = LoggerFactory.getLogger("FAILED_ORDERS_LOGGER");

    private final KafkaTemplate<String, Order> kafkaTemplate;

    @Value("${kafka.topic.name}")
    private String topicName;

    @Value("${producer.send.timeout.ms:10000}")
    private long sendTimeoutMs;

    public KafkaProducerService(KafkaTemplate<String, Order> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends an Order to Kafka synchronously.
     * Relies on Kafka's internal retries (configured in application.properties).
     */
    @CircuitBreaker(name = "cartService", fallbackMethod = "sendOrderFallback")
    public void sendOrder(String orderId, Order order) {
        try {
            // Synchronous block: Kafka retries internally until delivery.timeout.ms is reached
            SendResult<String, Order> result = kafkaTemplate.send(topicName, orderId, order)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);

            logger.info("Successfully sent orderId={} (partition={} offset={})",
                    orderId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());

        } catch (Exception e) {
            logFailedOrder("KAFKA_FAILURE", orderId, order, e.getMessage());
            
            // Throw specific exception for the fallback/handler to catch
            throw new ServiceUnavailableException("KAFKA_DOWN", orderId, 
                    "Message broker is unreachable or timed out", e);
        }
    }

    /**
     * Fallback method for Circuit Breaker.
     * Distinguishes between an open circuit and a direct Kafka failure.
     */
    @SuppressWarnings("unused")
    public void sendOrderFallback(String orderId, Order order, Throwable t) {
        String type;
        String message;

        if (t instanceof CallNotPermittedException) {
            type = "CIRCUIT_BREAKER_OPEN";
            message = "Service is temporarily unavailable due to high failure rate (Circuit Breaker Open)";
        } else if (t instanceof ServiceUnavailableException sue) {
            // Preserve the specific type (e.g., KAFKA_DOWN) thrown in the try block
            type = sue.getType();
            message = sue.getMessage();
        } else {
            type = "KAFKA_ERROR";
            message = "An unexpected error occurred while communicating with the message broker";
        }

        logger.error("Fallback triggered for orderId={} | Type: {} | Reason: {}", orderId, type, t.getMessage());
        logFailedOrder("FALLBACK_" + type, orderId, order, t.getMessage());
        
        throw new ServiceUnavailableException(type, orderId, message, t);
    }

    private void logFailedOrder(String type, String orderId, Order order, String reason) {
        failedOrdersLogger.info("FAILED_ORDER | Type: {} | OrderId: {} | Reason: {} | Payload: {}", 
                type, orderId, reason, order);
    }
}
