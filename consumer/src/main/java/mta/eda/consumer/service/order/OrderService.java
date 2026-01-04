package mta.eda.consumer.service.order;

import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.model.order.ProcessedOrder;
import mta.eda.consumer.service.util.StatusMachine;
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
     * 3. State Transition Validation: Prevent invalid status transitions using state machine
     * 4. Business Logic: Calculate shipping cost based on order items
     * 5. Update Local State: Save order and shipping cost
     * 6. Acknowledge: Called by KafkaConsumerService after successful processing
     *
     * State Machine Validation:
     * - NEW → CONFIRMED → DISPATCHED → COMPLETED (forward progression)
     * - CANCELED can be reached from any state (terminal)
     * - Backward transitions (e.g., DISPATCHED → NEW) are rejected
     * - All transitions must follow the defined state machine
     *
     * Idempotency Strategy:
     * - Exact Duplicate: If the incoming order has the same orderId AND same status as current state, skip it
     * - Invalid Transition: If the transition violates state machine rules, skip it
     * - Valid Transition: If the incoming status transition is allowed, process it
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

        // Step 2: State transition validation using shared StatusMachine
        // Check if the status transition is allowed by the state machine
        String currentStatus = current == null ? null : current.order().status();
        if (!StatusMachine.isValidTransition(currentStatus, order.status())) {
            logger.warn("⊘ Invalid Status Transition: Order {} cannot transition from '{}' to '{}'. Skipping.",
                    orderId, currentStatus, order.status());
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
