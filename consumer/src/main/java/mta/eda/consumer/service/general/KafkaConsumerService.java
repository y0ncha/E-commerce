package mta.eda.consumer.service.general;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.service.order.OrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerService.class);
    private final OrderService orderService;
    private final ObjectMapper objectMapper;


    @org.springframework.beans.factory.annotation.Autowired
    public KafkaConsumerService(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${kafka.consumer.topic}", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "kafkaListenerContainerFactory")
    public void listen(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String orderId = record.key();
        String value = record.value();

        logger.info("Received message for orderId: {}, partition: {}, offset: {}", orderId, record.partition(), record.offset());

        try {
            Order order = objectMapper.readValue(value, Order.class);

            // Validate orderId matches key (optional but good practice)
            if (orderId != null && !orderId.equals(order.orderId())) {
                 logger.warn("Message key {} does not match order body id {}", orderId, order.orderId());
            }

            orderService.processOrder(order);

            // Manual commit
            acknowledgment.acknowledge();

        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize message: {}", value, e);
            // Poison pill handling: acknowledge to move past the bad message
            acknowledgment.acknowledge();
        } catch (Exception e) {
            logger.error("Error processing message for orderId: {}", orderId, e);
            // Acknowledge avoiding blocking the consumer on non-recoverable errors
            acknowledgment.acknowledge();
        }
    }
}
