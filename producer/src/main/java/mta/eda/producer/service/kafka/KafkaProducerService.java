package mta.eda.producer.service.kafka;

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
 * Ensures response accuracy by aligning Kafka retries with the API blocking window.
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
     * Retries occur "online" within the 10s window.
     * Protected by 'cartService' circuit breaker.
     */
    @CircuitBreaker(name = "cartService", fallbackMethod = "sendOrderFallback")
    public void sendOrder(String orderId, Order order) {
        try {
            // Synchronous block: Kafka retries internally until 10s is reached
            SendResult<String, Order> result = kafkaTemplate.send(topicName, orderId, order)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);

            logger.info("Successfully sent orderId={} partition={} offset={}",
                    orderId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());

        } catch (Exception e) {
            // Log to DLQ for data safety
            logFailedOrder("KAFKA_FAILURE", orderId, order, e.getMessage());
            
            // Throw to trigger GlobalExceptionHandler (503)
            throw new ServiceUnavailableException("KAFKA_UNAVAILABLE", orderId, 
                    "Kafka delivery failed within the retry window", e);
        }
    }

    /**
     * Fallback method for Circuit Breaker.
     * Triggered when the circuit is OPEN or a call fails.
     */
    @SuppressWarnings("unused")
    public void sendOrderFallback(String orderId, Order order, Throwable t) {
        logger.error("Circuit Breaker Fallback for orderId={}. Reason: {}", orderId, t.getMessage());
        
        // Log to DLQ for data safety
        logFailedOrder("CIRCUIT_BREAKER_FALLBACK", orderId, order, t.getMessage());
        
        throw new ServiceUnavailableException("SERVICE_UNAVAILABLE", orderId,
                "Cart service is temporarily unavailable (Circuit Breaker Open or Kafka Down)", t);
    }

    private void logFailedOrder(String type, String orderId, Order order, String reason) {
        failedOrdersLogger.info("FAILED_ORDER | Type: {} | OrderId: {} | Reason: {} | Payload: {}", 
                type, orderId, reason, order);
    }
}
