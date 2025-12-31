package mta.eda.producer.service.kafka;

import mta.eda.producer.exception.ProducerSendException;
import mta.eda.producer.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * KafkaProducerService
 * Publishes Order messages to Kafka.
 * Uses orderId as the Kafka key to guarantee ordering per order.
 */
@Service
public class KafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);
    
    // Dedicated logger for Level 3 Data Safety (logs to failed-orders.log)
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
     * Sends an Order to Kafka synchronously with bounded timeout.
     *
     * @param orderId The order ID (used as Kafka message key for ordering)
     * @param order   The order to publish
     * @throws ProducerSendException if send fails (classified by type)
     */
    public void sendOrder(String orderId, Order order) {
        try {

            SendResult<String, Order> result =
                    kafkaTemplate.send(topicName, orderId, order)
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);

            logger.info("Successfully sent orderId={} partition={} offset={}",
                    orderId,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (TimeoutException e) {
            logFailedOrder("TIMEOUT", orderId, order, e.getMessage());
            throw new ProducerSendException("TIMEOUT", orderId,
                    "Send timeout after " + sendTimeoutMs + "ms", e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logFailedOrder("INTERRUPTED", orderId, order, e.getMessage());
            throw new ProducerSendException("INTERRUPTED", orderId,
                    "Send interrupted", e);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            logFailedOrder("KAFKA_ERROR", orderId, order, cause.getMessage());
            throw new ProducerSendException("KAFKA_ERROR", orderId,
                    "Kafka send failed: " + cause.getMessage(), cause);
        } catch (org.apache.kafka.common.KafkaException e) {
            logFailedOrder("KAFKA_ERROR", orderId, order, e.getMessage());
            throw new ProducerSendException("KAFKA_ERROR", orderId,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            logFailedOrder("UNEXPECTED", orderId, order, e.getMessage());
            throw new ProducerSendException("UNEXPECTED", orderId,
                    "Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Level 3 Data Safety: Logs the full order details to a dedicated file for manual recovery.
     */
    private void logFailedOrder(String type, String orderId, Order order, String reason) {
        logger.error("Level 3 Fallback: Logging failed orderId={} to failed-orders.log [Type: {}, Reason: {}]", 
                orderId, type, reason);
        
        // Log the full order object for manual re-processing
        failedOrdersLogger.info("FAILED_ORDER | Type: {} | OrderId: {} | Reason: {} | Payload: {}", 
                type, orderId, reason, order);
    }
}