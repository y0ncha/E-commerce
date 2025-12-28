package mta.eda.producer.service.order;

import mta.eda.producer.exception.DuplicateOrderException;
import mta.eda.producer.exception.OrderNotFoundException;
import mta.eda.producer.model.CreateOrderRequest;
import mta.eda.producer.model.UpdateOrderRequest;
import mta.eda.producer.model.Order;
import mta.eda.producer.model.OrderItem;
import mta.eda.producer.service.kafka.KafkaProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static mta.eda.producer.service.utils.OrderUtils.*;

/**
 * OrderService
 * Business logic for order operations.
 * Creates full Order objects and delegates to KafkaProducerService.
 */
@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    private final KafkaProducerService kafkaProducerService;
    private final Map<String, Order> orderStore = new ConcurrentHashMap<>();

    public OrderService(KafkaProducerService kafkaProducerService) {
        this.kafkaProducerService = kafkaProducerService;
    }

    /**
     * Creates a new order and publishes full Order to Kafka.
     *
     * @param request The create order request
     * @throws DuplicateOrderException if order with same orderId already exists
     */
    public Order createOrder(CreateOrderRequest request) {
        logger.info("Creating order with orderId={}, numItems={}",
                request.orderId(), request.numItems());

        // Generate customer ID
        String customerId = "CUST-" + String.format("%04d", random.nextInt(10000));

        // Generate order date (ISO-8601 format)
        String orderDate = Instant.now().toString();

        // Generate order items
        List<OrderItem> items = generateOrderItems(request.numItems());

        // Calculate total amount
        double totalAmount = calculateTotalAmount(items);

        // Create full Order
        Order order = new Order(
                request.orderId(),
                customerId,
                orderDate,
                items,
                totalAmount,
                "USD",
                "new"
        );

        // Store order in memory
        Order prev = orderStore.putIfAbsent(request.orderId(), order);
        if (prev != null) {
            throw new DuplicateOrderException(request.orderId());
        }

        // Publish to Kafka
        kafkaProducerService.sendOrder(request.orderId(), order);
        return order;
    }

    /**
     * Updates an existing order and publishes updated full Order to Kafka.
     *
     * @param request The update order request
     */
    public Order updateOrder(UpdateOrderRequest request) {
        logger.info("Updating order with orderId={}, status={}",
                request.orderId(), request.status());

        final Order[] updatedRef = new Order[1];

        orderStore.computeIfPresent(request.orderId(), (id, existingOrder) -> {
            Order updatedOrder = new Order(
                    existingOrder.orderId(),
                    existingOrder.customerId(),
                    existingOrder.orderDate(),
                    existingOrder.items(),
                    existingOrder.totalAmount(),
                    existingOrder.currency(),
                    request.status()
            );
            updatedRef[0] = updatedOrder;
            return updatedOrder;
        });

        if (updatedRef[0] == null) {
            throw new OrderNotFoundException(request.orderId());
        }

        kafkaProducerService.sendOrder(request.orderId(), updatedRef[0]);
        return updatedRef[0];
    }
}

