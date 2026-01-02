package mta.eda.consumer.service.order;

import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.model.order.ProcessedOrder;
import static mta.eda.consumer.service.util.OrderUtils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    // Map to store processed orders with calculated shipping costs (Phase 2: State Mirroring)
    private final Map<String, ProcessedOrder> processedOrderStore = new ConcurrentHashMap<>();

    /**
     * Phase 2: Core Event Processing Logic - State Mirroring Pattern
     * Processing Workflow:
     * 1. Receive & Deserialize (handled by KafkaConsumerService)
     * 2. Business Logic: Calculate shipping cost based on order items
     * 3. Idempotency & Sequencing Check: Prevent older events from overwriting newer state
     * 4. Update Local State: Save order and shipping cost
     * 5. Acknowledge: Called by KafkaConsumerService after successful processing
     */
    public void processOrder(Order order) {
        String orderId = order.orderId();
        ProcessedOrder current = processedOrderStore.get(orderId);

        // Step 1: Idempotency check - if we already have this exact order state, skip
        // This handles duplicate events (same orderId + same status = same event)
        if (current != null && current.order().equals(order)) {
            logger.info("Duplicate event: Order {} already in state {}. Skipping.",
                    orderId, order.status());
            return;
        }

        // Step 2: Sequencing check - prevent older status from overwriting newer one
        // Status order: CREATED -> CONFIRMED -> DISPATCHED -> DELIVERED
        if (current != null && !isValidTransition(current.order().status(), order.status())) {
            logger.warn("Invalid transition: Order {} cannot move from {} to {}. Rejecting update.",
                    orderId, current.order().status(), order.status());
            return;
        }

        // Step 3: Calculate shipping cost based on order items
        double shippingCost = calculateShippingCost(order);

        // Step 4: Update the local state with ProcessedOrder wrapper
        // Only executed if the order is new or valid transition
        ProcessedOrder processedOrder = new ProcessedOrder(order, shippingCost);
        processedOrderStore.put(orderId, processedOrder);

        String action = current == null ? "Created" : "Transitioned";
        logger.info("{} order {}. Status: {} -> {} | Shipping Cost: ${}",
                action, orderId,
                current == null ? "NEW" : current.order().status(),
                order.status(),
                String.format("%.2f", shippingCost));
    }

    /**
     * Get an order by its ID.
     * Normalizes the orderId with ORD- prefix before lookup.
     * @param rawOrderId the raw order ID (may or may not have ORD- prefix)
     * @return Optional containing the order if found
     */
    public Optional<Order> getOrder(String rawOrderId) {
        String normalizedOrderId = normalizeOrderId(rawOrderId);
        return Optional.ofNullable(processedOrderStore.get(normalizedOrderId))
                .map(ProcessedOrder::order);
    }

    /**
     * Get a processed order (order and shipping cost) by its ID.
     * Normalizes the orderId with ORD- prefix before lookup.
     * @param rawOrderId the raw order ID (may or may not have ORD- prefix)
     * @return Optional containing the processed order if found
     */
    public Optional<ProcessedOrder> getProcessedOrder(String rawOrderId) {
        String normalizedOrderId = normalizeOrderId(rawOrderId);
        return Optional.ofNullable(processedOrderStore.get(normalizedOrderId));
    }

    /**
     * Validate status transitions to prevent older events from overwriting newer states.
     * Allowed transitions: CREATED -> CONFIRMED -> DISPATCHED -> DELIVERED
     *
     * @param currentStatus the current status
     * @param newStatus the new status
     * @return true if transition is valid, false otherwise
     */
    private boolean isValidTransition(String currentStatus, String newStatus) {
        // If the same status, it's a duplicate (handled by equals check above)
        if (currentStatus.equals(newStatus)) {
            return true;
        }

        // Define valid transitions
        return switch (currentStatus) {
            case "CREATED" -> newStatus.equals("CONFIRMED") || newStatus.equals("DISPATCHED") || newStatus.equals("DELIVERED");
            case "CONFIRMED" -> newStatus.equals("DISPATCHED") || newStatus.equals("DELIVERED");
            case "DISPATCHED" -> newStatus.equals("DELIVERED");
            case "DELIVERED" -> false; // No transitions from DELIVERED
            default -> true; // Allow any transition for unknown statuses (defensive)
        };
    }

    /**
     * Retrieve all calculated shipping costs.
     *
     * @return map of orders keyed by orderId
     */
    public Map<String, Order> getAllOrders() {
        return processedOrderStore.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().order()
                ));
    }

    /**
     * Retrieve all processed orders (with shipping costs).
     *
     * @return map of processed orders keyed by orderId
     */
    public Map<String, ProcessedOrder> getAllProcessedOrders() {
        return new java.util.concurrent.ConcurrentHashMap<>(processedOrderStore);
    }

    /**
     * Retrieve all calculated shipping costs.
     *
     * @return map of shipping costs keyed by orderId
     */
    public Map<String, Double> getAllShippingCosts() {
        return processedOrderStore.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().shippingCost()
                ));
    }
}


