package mta.eda.consumer.service.order;

import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.model.order.ProcessedOrder;
import static mta.eda.consumer.service.util.OrderUtils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    // Map to store processed orders with calculated shipping costs (Phase 2: State Mirroring)
    private final Map<String, ProcessedOrder> processedOrderStore = new ConcurrentHashMap<>();
    // List to store ALL messages received (for debugging/tracking all events including duplicates)
    private final List<ProcessedOrder> allMessages = new CopyOnWriteArrayList<>();

    /**
     * Phase 2: Core Event Processing Logic - State Mirroring Pattern with Idempotency
     * Processing Workflow:
     * 1. Receive & Deserialize (handled by KafkaConsumerService)
     * 2. Idempotency Check: Detect exact duplicates (same orderId + same status)
     * 3. State Transition Validation: Prevent older status from overwriting newer state
     * 4. Business Logic: Calculate shipping cost based on order items
     * 5. Update Local State: Save order and shipping cost
     * 6. Acknowledge: Called by KafkaConsumerService after successful processing
     *
     * Idempotency Strategy:
     * - Exact Duplicate: If the incoming order has the same orderId AND same status as current state, skip it
     * - Out-of-Order Event: If the incoming status represents an older state than current, skip it
     * - Valid Transition: If the incoming status is newer or first-time, process it
     *
     * Status Progression (from oldest to newest):
     * NEW < CONFIRMED < DISPATCHED < COMPLETED
     *
     * Examples:
     * - Current: DISPATCHED, Incoming: NEW → Skip (older state)
     * - Current: CONFIRMED, Incoming: DISPATCHED → Process (valid transition)
     * - Current: CONFIRMED, Incoming: CONFIRMED → Skip (duplicate)
     */
    public void processOrder(Order order) {
        String orderId = order.orderId();
        ProcessedOrder current = processedOrderStore.get(orderId);

        // Step 1: Idempotency check - if we already have this exact order state, skip
        // This handles duplicate events (same orderId + same status = same event)
        if (current != null && current.order().status().equals(order.status())) {
            logger.info("⊘ Idempotency Check: Order {} already in state '{}'. Skipping duplicate.",
                    orderId, order.status());
            return;
        }

        // Step 2: State transition validation - prevent older status from corrupting newer state
        // Compare status progression: only allow updates if new status >= current status
        if (current != null && !isValidStatusTransition(current.order().status(), order.status())) {
            logger.warn("⊘ Out-of-Order Event: Order {} is already in '{}', rejecting older status '{}'. Skipping.",
                    orderId, current.order().status(), order.status());
            return;
        }

        // Step 3: Calculate shipping cost based on order items
        double shippingCost = calculateShippingCost(order);

        // Step 4: Update the local state with ProcessedOrder wrapper
        Instant receivedAt = Instant.now();
        ProcessedOrder processedOrder = new ProcessedOrder(order, shippingCost, receivedAt);
        processedOrderStore.put(orderId, processedOrder);

        // Track ALL messages received (including all status updates)
        allMessages.add(processedOrder);

        String action = current == null ? "Created" : "Updated";
        logger.info("✓ {} order {}. Status: {} -> {} | Shipping Cost: ${}",
                action, orderId,
                current == null ? "NEW" : current.order().status(),
                order.status(),
                String.format("%.2f", shippingCost));
    }

    /**
     * Validates if a status transition is valid based on status progression order.
     *
     * Status Order (from lowest to highest):
     * NEW (0) < CONFIRMED (1) < DISPATCHED (2) < COMPLETED (3)
     *
     * A transition is valid if: newStatus.order >= currentStatus.order
     * This prevents out-of-order messages from corrupting the state.
     *
     * @param currentStatus the current status in the store
     * @param newStatus the incoming status from Kafka message
     * @return true if the transition is valid (new >= current), false otherwise
     */
    private boolean isValidStatusTransition(String currentStatus, String newStatus) {
        int currentOrder = getStatusOrder(currentStatus);
        int newOrder = getStatusOrder(newStatus);

        // Valid if new status order >= current status order
        return newOrder >= currentOrder;
    }

    /**
     * Returns the order/priority of a status in the progression.
     * Lower number = earlier stage, Higher number = later stage.
     *
     * @param status the order status
     * @return the order number (0-3), or -1 for unknown status
     */
    private int getStatusOrder(String status) {
        if (status == null) {
            return -1;
        }

        return switch (status.toUpperCase()) {
            case "NEW" -> 0;
            case "CONFIRMED" -> 1;
            case "DISPATCHED" -> 2;
            case "COMPLETED" -> 3;
            default -> {
                logger.warn("Unknown status encountered: {}. Treating as lowest priority.", status);
                yield -1; // Unknown statuses are treated as lowest priority
            }
        };
    }

    /**
     * Get an order by its ID.
     * Normalizes the orderId with ORD- prefix before lookup.
     *
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
     *
     * @param rawOrderId the raw order ID (may or may not have ORD- prefix)
     * @return Optional containing the processed order if found
     */
    public Optional<ProcessedOrder> getProcessedOrder(String rawOrderId) {
        String normalizedOrderId = normalizeOrderId(rawOrderId);
        return Optional.ofNullable(processedOrderStore.get(normalizedOrderId));
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
     * Retrieve ALL messages received (including all status updates for same order).
     * This returns every message that was processed, not just the latest state.
     *
     * @return list of all processed messages sorted by arrival time
     */
    public List<ProcessedOrder> getAllMessages() {
        return new java.util.ArrayList<>(allMessages);
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

    /**
     * Retrieve all order IDs received from a specific Kafka topic.
     * Historical Tracking: Returns orders that were consumed from the specified topic.
     * <p>
     * Educational Note:
     * This method demonstrates the historical tracking requirement from Exercise 2.
     * Every order stores its source topic (from ConsumerRecord.topic()) at consumption time.
     * This enables answering the question: "Which orders were received from topic X?"
     * <p>
     * At-Least-Once Integrity:
     * - topicName is saved before calling acknowledgment.acknowledge()
     * - If processing fails before acknowledgment, topicName is not saved
     * - This maintains consistency: saved orders = successfully processed messages
     *
     * @param topicName the Kafka topic name to filter by
     * @return list of order IDs received from that topic (order may vary)
     */
    public java.util.List<String> getOrdersByTopic(String topicName) {
        return processedOrderStore.entrySet().stream()
                .filter(entry -> topicName.equals(entry.getValue().order().topicName()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
