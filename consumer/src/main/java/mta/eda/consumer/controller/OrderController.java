package mta.eda.consumer.controller;

import jakarta.validation.Valid;
import mta.eda.consumer.exception.InvalidOrderIdException;
import mta.eda.consumer.exception.OrderNotFoundException;
import mta.eda.consumer.model.order.ProcessedOrder;
import mta.eda.consumer.model.request.AllOrdersFromTopicRequest;
import mta.eda.consumer.model.request.OrderDetailsRequest;
import mta.eda.consumer.model.response.HealthCheck;
import mta.eda.consumer.model.response.HealthResponse;
import mta.eda.consumer.service.general.HealthService;
import mta.eda.consumer.service.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    public OrderController(OrderService orderService, HealthService healthService) {
        this.orderService = orderService;
        this.healthService = healthService;
    }

    /**
     * Root endpoint - provides API metadata and documentation.
     * Symmetric to producer's root endpoint.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> root() {
        logger.debug("Root endpoint accessed");

        Map<String, Object> response = new HashMap<>();
        response.put("service", "Order Service (Consumer)");
        response.put("timestamp", Instant.now().toString());

        Map<String, Object> endpoints = new HashMap<>();

        // Health endpoints
        Map<String, Object> health = new HashMap<>();
        health.put("live", new HashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/order-service/health/live");
            put("description", "Liveness probe");
        }});
        health.put("ready", new HashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/order-service/health/ready");
            put("description", "Readiness probe");
        }});

        // Order endpoints
        Map<String, Object> orders = new HashMap<>();
        orders.put("orderDetails", new HashMap<String, Object>() {{
            put("method", "POST");
            put("path", "/order-service/order-details");
            put("description", "Get order details");
        }});
        orders.put("getAllOrdersFromTopic", new HashMap<String, Object>() {{
            put("method", "POST");
            put("path", "/order-service/getAllOrdersFromTopic");
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

        boolean isKafkaUp = "UP".equals(kafkaStatus.status());
        String overallStatus = isKafkaUp ? "UP" : "DOWN";

        Map<String, HealthCheck> checks = Map.of(
            "service", serviceStatus,
            "kafka", kafkaStatus,
            "local-state", stateStatus
        );

        HealthResponse response = new HealthResponse(
            "Order Service (Consumer)",
            "readiness",
            overallStatus,
            Instant.now().toString(),
            checks
        );

        HttpStatus httpStatus = isKafkaUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Get order details by orderId.
     *
     * @param request OrderDetailsRequest containing orderId (hex format validation)
     * @return Order details with calculated shipping cost
     * @throws OrderNotFoundException if order is not found
     * @throws InvalidOrderIdException if orderId format is invalid
     */
    @PostMapping("/order-details")
    public ResponseEntity<Map<String, Object>> getOrderDetails(@Valid @RequestBody OrderDetailsRequest request) {
        String rawOrderId = request.orderId();
        logger.info("Received order details request: orderId={}", rawOrderId);

        try {
            // Query the order service (normalizeOrderId is called internally)
            Optional<ProcessedOrder> processedOrder = orderService.getProcessedOrder(rawOrderId);

            if (processedOrder.isEmpty()) {
                logger.warn("Order not found: orderId={}", rawOrderId);
                throw new OrderNotFoundException(rawOrderId);
            }

            ProcessedOrder order = processedOrder.get();
            logger.info("Order found: orderId={}, status={}, shippingCost={}",
                rawOrderId, order.order().status(), String.format("%.2f", order.shippingCost()));

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", order.order().orderId());
            response.put("customerId", order.order().customerId());
            response.put("orderDate", order.order().orderDate());
            response.put("status", order.order().status());
            response.put("items", order.order().items());
            response.put("totalAmount", order.order().totalAmount());
            response.put("currency", order.order().currency());
            response.put("shippingCost", String.format("%.2f", order.shippingCost()));

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Thrown by normalizeOrderId for invalid hex format
            throw new InvalidOrderIdException(rawOrderId, e.getMessage());
        }
    }

    /**
     * Get all order IDs from a specific Kafka topic.
     * Diagnostic endpoint for Exercise 2 requirements.
     *
     * @param request AllOrdersFromTopicRequest containing topic name
     * @return List of all processed order IDs from that topic
     */
    @PostMapping("/getAllOrdersFromTopic")
    public ResponseEntity<Map<String, Object>> getAllOrdersFromTopic(
            @Valid @RequestBody AllOrdersFromTopicRequest request) {
        String topicName = request.topicName();
        logger.info("Received request for all orders from topic: {}", topicName);

        // Get all order IDs
        java.util.List<String> orderIds = orderService.getAllOrders().keySet().stream().toList();

        logger.info("Found {} orders in topic '{}'", orderIds.size(), topicName);

        Map<String, Object> response = new HashMap<>();
        response.put("topic", topicName);
        response.put("orderCount", orderIds.size());
        response.put("orderIds", orderIds);
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }
}

