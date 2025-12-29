package mta.eda.producer.service.order;

import mta.eda.producer.exception.DuplicateOrderException;
import mta.eda.producer.exception.InvalidOrderIdException;
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

        // Normalize orderId to match CUST-#### and ITEM-#### format
        String normalizedOrderId = normalizeOrderId(request.orderId());

        // Generate customer ID (hex format for 65,536 unique IDs)
        String customerId = "CUST-" + String.format("%04X", random.nextInt(0x10000));

        // Generate order date (ISO-8601 format)
        String orderDate = Instant.now().toString();

        // Generate order items
        List<OrderItem> items = generateOrderItems(request.numItems());

        // Calculate total amount
        double totalAmount = calculateTotalAmount(items);

        // Create full Order
        Order order = new Order(
                normalizedOrderId,
                customerId,
                orderDate,
                items,
                totalAmount,
                "USD",
                "new"
        );

        // Store order in memory
        Order prev = orderStore.putIfAbsent(normalizedOrderId, order);
        if (prev != null) {
            throw new DuplicateOrderException(normalizedOrderId);
        }

        // Publish to Kafka
        kafkaProducerService.sendOrder(normalizedOrderId, order);
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

        // Normalize orderId to match CUST-#### and ITEM-#### format
        String normalizedOrderId = normalizeOrderId(request.orderId());

        final Order[] updatedRef = new Order[1];

        orderStore.computeIfPresent(normalizedOrderId, (id, existingOrder) -> {
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
            throw new OrderNotFoundException(normalizedOrderId);
        }

        kafkaProducerService.sendOrder(normalizedOrderId, updatedRef[0]);
        return updatedRef[0];
    }

    /**
     * Formats orderId with the ORD- prefix.
     * Accepts any length of hexadecimal characters from the user (1 or more).
     * If less than 4 characters, pads with leading zeros. Otherwise keeps as-is.
     *
     * @param rawOrderId The raw order ID symbols from the request (e.g., "007B", "1", "ABCD1234")
     * @return Formatted orderId in format ORD-#### or longer (e.g., ORD-007B, ORD-000A, ORD-ABCD1234)
     */
    private String normalizeOrderId(String rawOrderId) {
        // Trim whitespace and convert to uppercase
        String symbols = rawOrderId.trim().toUpperCase();

        // Validate: only hex characters (0-9, A-F), at least 1 character
        if (!symbols.matches("[0-9A-F]+")) {
            throw new InvalidOrderIdException(rawOrderId);
        }

        // Pad to at least 4 characters with leading zeros if shorter
        String padded;
        if (symbols.length() < 4) {
            padded = String.format("%4s", symbols).replace(' ', '0');
        } else {
            padded = symbols;
        }

        String formatted = "ORD-" + padded;
        logger.debug("Formatted orderId: '{}' -> '{}'", rawOrderId, formatted);
        return formatted;
    }
}
