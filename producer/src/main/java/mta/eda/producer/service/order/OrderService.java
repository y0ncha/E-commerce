 package mta.eda.producer.service.order;

import mta.eda.producer.exception.*;
import mta.eda.producer.model.request.CreateOrderRequest;
import mta.eda.producer.model.request.UpdateOrderRequest;
import mta.eda.producer.model.order.Order;
import mta.eda.producer.model.order.OrderItem;
import mta.eda.producer.service.kafka.KafkaProducerService;
import mta.eda.producer.service.util.StatusMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static mta.eda.producer.service.util.OrderUtils.*;

/**
 * OrderService
 * Business logic for order operations.
 * Creates full Order objects and delegates to KafkaProducerService.
 */
@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    private final KafkaProducerService kafkaProducerService;
    
    // In-memory order store (Source of Truth for the API)
    private final Map<String, Order> orderStore = new ConcurrentHashMap<>();
    
    // Internal Dead Letter Queue (DLQ) for failed Kafka messages
    private final Map<String, Order> failedMessages = new ConcurrentHashMap<>();

    public OrderService(KafkaProducerService kafkaProducerService) {
        this.kafkaProducerService = kafkaProducerService;
    }

    /**
     * Creates a new order and publishes full Order to Kafka.
     */
    public Order createOrder(CreateOrderRequest request) {
        logger.info("Creating order with orderId={}, numItems={}",
                request.orderId(), request.numItems());

        String normalizedOrderId = normalizeOrderId(request.orderId());
        String customerId = "CUST-" + String.format("%04X", random.nextInt(0x10000));
        String orderDate = Instant.now().toString();
        List<OrderItem> items = generateOrderItems(request.numItems());
        double totalAmount = calculateTotalAmount(items);

        Order order = new Order(
                normalizedOrderId,
                customerId,
                orderDate,
                items,
                totalAmount,
                "USD",
                "new"
        );

        // 1. Pessimistic Locking: Reserve the ID in the local store
        Order prev = orderStore.putIfAbsent(normalizedOrderId, order);
        if (prev != null) {
            throw new DuplicateOrderException(normalizedOrderId);
        }

        try {
            // 2. Attempt Kafka transmission
            kafkaProducerService.sendOrder(normalizedOrderId, order);
            
            // Success: Ensure it's removed from DLQ if it was there previously
            failedMessages.remove(normalizedOrderId);
            
            return order;
            
        } catch (Exception e) {
            // 3. ROLLBACK: Remove from local store so user can retry
            orderStore.remove(normalizedOrderId);
            
            // 4. DATA SAFETY: Save to internal DLQ for later processing
            failedMessages.put(normalizedOrderId, order);
            
            logger.error("Failed to send to Kafka. Rolled back local store and saved to internal DLQ for orderId={}", normalizedOrderId);
            throw e;
        }
    }

    /**
     * Updates an existing order and publishes updated full Order to Kafka.
     *
     * Status Transition Validation:
     * Enforces the state machine rules to ensure only valid transitions occur:
     * - NEW → CONFIRMED → DISPATCHED → COMPLETED (sequential progression)
     * - CANCELED can be reached from any state (terminal)
     * - Backward transitions are NOT allowed (e.g., DISPATCHED → CONFIRMED)
     * - Skipping states is NOT allowed (e.g., NEW → DISPATCHED)
     *
     * @param request the update request containing orderId and new status
     * @return the updated Order
     * @throws OrderNotFoundException if order doesn't exist
     * @throws OrderStatusConflictException if order already has the requested status
     * @throws InvalidStatusException if the status value is invalid/unknown
     * @throws InvalidStatusTransitionException if transition violates state machine
     */
    public Order updateOrder(UpdateOrderRequest request) {
        logger.info("Updating order with orderId={}, status={}", request.orderId(), request.status());
        String normalizedOrderId = normalizeOrderId(request.orderId());

        Order existingOrder = orderStore.get(normalizedOrderId);
        if (existingOrder == null) {
            throw new OrderNotFoundException(normalizedOrderId);
        }

        // Idempotency guard: if status is unchanged, do nothing
        if (existingOrder.status().equals(request.status())) {
            logger.warn("✗ Status conflict: orderId={} already in status '{}'", normalizedOrderId, existingOrder.status());
            throw new OrderStatusConflictException(normalizedOrderId, existingOrder.status());
        }

        // Validate status value: Check if the requested status is valid
        if (StatusMachine.getStatusOrder(request.status()) == -1) {
            logger.error("✗ Invalid status value '{}' for order {}", request.status(), normalizedOrderId);
            throw new InvalidStatusException(normalizedOrderId, request.status());
        }

        // State Machine Validation: Ensure status transition is valid
        if (!StatusMachine.isValidTransition(existingOrder.status(), request.status())) {
            String errorMsg = String.format(
                "✗ Invalid status transition for order %s: %s → %s",
                normalizedOrderId, existingOrder.status(), request.status()
            );
            logger.error(errorMsg);
            throw new InvalidStatusTransitionException(normalizedOrderId, existingOrder.status(), request.status());
        }

        Order updatedOrder = new Order(
                existingOrder.orderId(),
                existingOrder.customerId(),
                existingOrder.orderDate(),
                existingOrder.items(),
                existingOrder.totalAmount(),
                existingOrder.currency(),
                request.status()
        );

        // 1. Optimistic Update: Update local store first
        orderStore.put(normalizedOrderId, updatedOrder);

        try {
            // 2. Attempt Kafka transmission
            kafkaProducerService.sendOrder(normalizedOrderId, updatedOrder);
            
            // Success: Ensure it's removed from DLQ if it was there previously
            failedMessages.remove(normalizedOrderId);
            
            logger.info("✓ Successfully updated order {}. Status: {} → {}",
                    normalizedOrderId, existingOrder.status(), request.status());

            return updatedOrder;
            
        } catch (Exception e) {
            // 3. ROLLBACK: Restore original state to maintain consistency
            orderStore.put(normalizedOrderId, existingOrder);
            
            // 4. DATA SAFETY: Save failed update to internal DLQ
            failedMessages.put(normalizedOrderId, updatedOrder);
            
            logger.error("✗ Failed to update Kafka. Rolled back local store and saved to internal DLQ for orderId={}", normalizedOrderId);
            throw e;
        }
    }

    /**
     * Returns the collection of failed Kafka messages for monitoring/recovery.
     */
    public Collection<Order> getFailedMessages() {
        return failedMessages.values();
    }
}
