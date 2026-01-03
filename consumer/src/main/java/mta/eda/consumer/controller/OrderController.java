package mta.eda.consumer.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import mta.eda.consumer.exception.InvalidOrderIdException;
import mta.eda.consumer.exception.OrderNotFoundException;
import mta.eda.consumer.model.order.ProcessedOrder;
import mta.eda.consumer.model.request.AllOrdersFromTopicRequest;
import mta.eda.consumer.model.request.OrderDetailsRequest;
import mta.eda.consumer.model.response.HealthCheck;
import mta.eda.consumer.model.response.HealthResponse;
import mta.eda.consumer.service.general.HealthService;
import mta.eda.consumer.service.kafka.KafkaConnectivityService;
import mta.eda.consumer.service.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static mta.eda.consumer.service.util.OrderUtils.normalizeOrderId;

/**
 * OrderController
 * REST API endpoints for order operations.
 */
@RestController
@RequestMapping("/order-service")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final HealthService healthService;
    private final KafkaConnectivityService kafkaConnectivityService;
    private final Validator validator;

    public OrderController(OrderService orderService, HealthService healthService,
                          KafkaConnectivityService kafkaConnectivityService, Validator validator) {
        this.orderService = orderService;
        this.healthService = healthService;
        this.kafkaConnectivityService = kafkaConnectivityService;
        this.validator = validator;
    }

    /**
     * Root endpoint - provides API metadata and documentation.
     * Symmetric to producer's root endpoint.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> root() {
        logger.debug("Root endpoint accessed");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "Order Service (Consumer)");
        response.put("timestamp", Instant.now().toString());

        Map<String, Object> endpoints = new LinkedHashMap<>();

        // Health endpoints
        Map<String, Object> health = new HashMap<>();
        health.put("live", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/order-service/health/live");
            put("description", "Liveness probe");
        }});
        health.put("ready", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/order-service/health/ready");
            put("description", "Readiness probe");
        }});

        // Order endpoints
        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("orderDetails", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/order-service/order-details?orderId=001");
            put("description", "Get order details");
        }});
        orders.put("getAllOrdersFromTopic", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/order-service/getAllOrdersFromTopic?topicName=orders");
            put("description", "Get all order IDs from topic");
        }});

        endpoints.put("health", health);
        endpoints.put("orders", orders);
        response.put("endpoints", endpoints);

        return ResponseEntity.ok(response);
    }

    /**
     * Liveness probe.
     */
    @GetMapping("/health/live")
    public ResponseEntity<HealthResponse> live() {
        logger.debug("Liveness probe called");

        HealthCheck serviceStatus = healthService.getServiceStatus();
        Map<String, HealthCheck> checks = Map.of("service", serviceStatus);
        HealthResponse response = new HealthResponse(
            "Order Service (Consumer)",
            "liveness",
            "UP",
            Instant.now().toString(),
            checks
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Readiness probe.
     */
    @GetMapping("/health/ready")
    public ResponseEntity<HealthResponse> ready() {
        logger.debug("Readiness probe called");

        HealthCheck serviceStatus = healthService.getServiceStatus();
        HealthCheck kafkaStatus = healthService.getKafkaStatus();
        HealthCheck stateStatus = healthService.getLocalStateStatus();

        // Core readiness is determined by the service itself and local state store.
        // Kafka may be temporarily unavailable, but the HTTP API can still serve requests.
        boolean coreUp = "UP".equals(serviceStatus.status()) && "UP".equals(stateStatus.status());

        Map<String, HealthCheck> checks = Map.of(
            "service", serviceStatus,
            "kafka", kafkaStatus,
            "local-state", stateStatus
        );

        HealthResponse response = new HealthResponse(
            "Order Service (Consumer)",
            "readiness",
            coreUp ? "UP" : "DOWN",
            Instant.now().toString(),
            checks
        );

        // Keep HTTP 200 when core is healthy, even if Kafka is DOWN (degraded but usable).
        HttpStatus httpStatus = coreUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Get order details by orderId.
     * Uses OrderDetailsRequest DTO for validation (see model.request package).
     * Validation Rules (from OrderDetailsRequest):
     * ├─ orderId is required (not null/blank)
     * └─ orderId must be hexadecimal format (0-9, A-F)
     * @param orderId the order ID (query parameter)
     * @return Order details with calculated shipping cost
     * @throws InvalidOrderIdException if orderId format is invalid
     * @throws OrderNotFoundException if order is not found
     */
    @GetMapping("/order-details")
    public ResponseEntity<Map<String, Object>> getOrderDetails(
            @RequestParam(required = false) String orderId) {

        // Create DTO from query parameter for validation
        OrderDetailsRequest request = new OrderDetailsRequest(orderId);

        // Validate using the DTO's constraint annotations
        Set<ConstraintViolation<OrderDetailsRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining("; "));
            logger.warn("Validation failed for order-details: {}", errorMessage);
            throw new InvalidOrderIdException(orderId != null ? orderId : "null", errorMessage);
        }

        logger.info("Received order details request: orderId={}", orderId);
        try {
            String normalizedOrderId = normalizeOrderId(orderId);
            Optional<ProcessedOrder> processedOrder = orderService.getProcessedOrder(normalizedOrderId);

            if (processedOrder.isEmpty()) {
                logger.warn("Order not found: orderId={}", normalizedOrderId);
                throw new OrderNotFoundException(orderId);
            }

            ProcessedOrder order = processedOrder.get();
            logger.info("Order found: orderId={}, status={}, shippingCost={}",
                    normalizedOrderId, order.order().status(), String.format("%.2f", order.shippingCost()));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("orderId", order.order().orderId());
            response.put("customerId", order.order().customerId());
            response.put("orderDate", order.order().orderDate());
            response.put("items", order.order().items());
            response.put("totalAmount", order.order().totalAmount());
            response.put("shippingCost", order.shippingCost());
            response.put("currency", order.order().currency());
            response.put("status", order.order().status());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Thrown by normalizeOrderId for invalid hex format
            throw new InvalidOrderIdException(orderId, e.getMessage());
        }
    }

    /**
     * Get all order IDs received from a specific Kafka topic.
     * Uses AllOrdersFromTopicRequest DTO for validation (see model.request package).
     * Historical Tracking:
     * - Returns all orders that were consumed from the specified topic
     * - Each order saves its source topic at consumption time (from ConsumerRecord.topic())
     * - This endpoint queries the local state store for orders matching that topic
     * Validation Rules (from AllOrdersFromTopicRequest):
     * └─ topicName is required (not null/blank)
     * @param topicName the Kafka topic name to query (query parameter)
     * @return List of all processed order IDs received from that topic
     */
    @GetMapping("/getAllOrdersFromTopic")
    public ResponseEntity<Map<String, Object>> getAllOrdersFromTopic(
            @RequestParam(required = false) String topicName) {

        // Create DTO from query parameter for validation
        AllOrdersFromTopicRequest request = new AllOrdersFromTopicRequest(topicName);

        // Validate using the DTO's constraint annotations
        Set<ConstraintViolation<AllOrdersFromTopicRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining("; "));
            logger.warn("Validation failed for getAllOrdersFromTopic: {}", errorMessage);
            throw new InvalidOrderIdException(topicName != null ? topicName : "null", errorMessage);
        }

        logger.info("Received request for all orders from topic: {}", topicName);

        // Query local storage for orders received from this topic
        java.util.List<String> orderIds = orderService.getOrdersByTopic(topicName).stream()
                .map(id -> {
                    try {
                        return normalizeOrderId(id);
                    } catch (IllegalArgumentException ex) {
                        return id;
                    }
                })
                .toList();

        logger.info("Found {} orders from topic '{}'", orderIds.size(), topicName);

        Map<String, Object> response = new HashMap<>();
        response.put("topic", topicName);
        response.put("orderCount", orderIds.size());
        response.put("orderIds", orderIds);
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }

    /**
     * Debug endpoint - Get all stored order IDs.
     * Useful for troubleshooting: shows all orders the consumer has processed.
     * @return List of all order IDs in local storage
     */
    @GetMapping("/debug/all-orders")
    public ResponseEntity<Map<String, Object>> getAllStoredOrders() {
        logger.info("Debug: Fetching all stored orders");

        Map<String, ProcessedOrder> allOrders = orderService.getAllProcessedOrders();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalOrders", allOrders.size());
        response.put("orderIds", allOrders.keySet());
        response.put("timestamp", Instant.now().toString());

        logger.info("Debug: Found {} total orders in storage", allOrders.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Debug endpoint - Get Kafka connectivity status.
     * Shows detailed info about Kafka connection and listener state.
     * @return Detailed Kafka status
     */
    @GetMapping("/debug/kafka-status")
    public ResponseEntity<Map<String, Object>> getKafkaStatus() {
        logger.info("Debug: Fetching Kafka connectivity status");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("connected", kafkaConnectivityService.isKafkaConnected());
        response.put("listenersRunning", kafkaConnectivityService.areListenersRunning());
        response.put("detailedStatus", kafkaConnectivityService.getDetailedStatus());
        response.put("bootstrapServers", kafkaConnectivityService.getBootstrapServers());
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }
}
