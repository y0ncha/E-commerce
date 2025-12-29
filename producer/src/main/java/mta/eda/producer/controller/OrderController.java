package mta.eda.producer.controller;

import jakarta.validation.Valid;
import mta.eda.producer.model.CreateOrderRequest;
import mta.eda.producer.model.HealthCheck;
import mta.eda.producer.model.HealthResponse;
import mta.eda.producer.model.Order;
import mta.eda.producer.model.UpdateOrderRequest;
import mta.eda.producer.service.kafka.KafkaHealthService;
import mta.eda.producer.service.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OrderController
 * REST API endpoints for order operations.
 */
@RestController
@RequestMapping("/cart-service")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final KafkaHealthService kafkaHealthService;

    public OrderController(OrderService orderService, KafkaHealthService kafkaHealthService) {
        this.orderService = orderService;
        this.kafkaHealthService = kafkaHealthService;
    }

    /**
     * Server metadata endpoint - exposes service info and available endpoints.
     * GET /cart-service
     * GET /cart-service/
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> root() {
        logger.debug("Root endpoint accessed");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "Producer (Cart Service)");
        response.put("version", "0.0.1-SNAPSHOT");
        response.put("timestamp", Instant.now().toString());

        Map<String, Object> endpoints = new LinkedHashMap<>();

        // Health endpoints
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("live", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/cart-service/health/live");
            put("description", "Liveness probe - checks if service process is running");
            put("kafkaDependency", false);
            put("responses", Map.of("200", "Service is alive"));
        }});
        health.put("ready", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/cart-service/health/ready");
            put("description", "Readiness probe - checks if service can handle requests");
            put("kafkaDependency", true);
            put("checks", List.of("Kafka broker reachable", "Topic 'order-events' exists"));
            put("responses", Map.of(
                "200", "Service ready - Kafka UP",
                "503", "Service not ready - Kafka DOWN"
            ));
        }});

        // Order endpoints
        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("createOrder", new LinkedHashMap<String, Object>() {{
            put("method", "POST");
            put("path", "/cart-service/create-order");
            put("description", "Create new order and publish to Kafka");
            put("body", Map.of(
                "orderId", "hex string (0-9, A-F, any length) - will be formatted as ORD-#### (e.g., 'A' → 'ORD-000A')",
                "numItems", "integer (required, min: 1, max: 100)"
            ));
            put("responses", Map.of(
                "201", "Order created and published to Kafka successfully",
                "400", "Bad request: validation error, invalid orderId format, or malformed JSON",
                "409", "Conflict: duplicate orderId",
                "500", "Internal server error: Kafka send failed"
            ));
        }});
        orders.put("updateOrder", new LinkedHashMap<String, Object>() {{
            put("method", "PUT");
            put("path", "/cart-service/update-order");
            put("description", "Update existing order status and publish to Kafka");
            put("body", Map.of(
                "orderId", "hex string (0-9, A-F, any length) - will be formatted as ORD-####",
                "status", "string (required, non-blank)"
            ));
            put("responses", Map.of(
                "200", "Order updated and published to Kafka successfully",
                "400", "Bad request: validation error, invalid orderId format, or malformed JSON",
                "404", "Not found: order does not exist",
                "500", "Internal server error: Kafka send failed"
            ));
        }});

        endpoints.put("health", health);
        endpoints.put("orders", orders);
        response.put("endpoints", endpoints);

        return ResponseEntity.ok(response);
    }

    /**
     * Liveness - app is running (does not depend on Kafka).
     * Only checks if service is alive.
     * GET /cart-service/health/live
     */
    @GetMapping("/health/live")
    public ResponseEntity<HealthResponse> live() {
        HealthCheck serviceStatus = kafkaHealthService.getServiceStatus();

        Map<String, HealthCheck> checks = Map.of(
                "service", serviceStatus
        );

        HealthResponse response = new HealthResponse(
                "Producer (Cart Service)",
                "liveness",
                "UP",  // liveness is always UP if service is running
                Instant.now().toString(),
                checks
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Readiness - checks whether the service can handle requests.
     * Checks both service and Kafka readiness.
     * Returns 200 when both are ready, otherwise 503.
     * GET /cart-service/health/ready
     */
    @GetMapping("/health/ready")
    public ResponseEntity<HealthResponse> ready() {
        HealthCheck serviceStatus = kafkaHealthService.getServiceStatus();
        HealthCheck kafkaStatus = kafkaHealthService.getKafkaStatus();

        // Overall ready only if both service and kafka are UP
        boolean isKafkaUp = "UP".equals(kafkaStatus.status());
        String overallStatus = isKafkaUp ? "UP" : "DOWN";

        Map<String, HealthCheck> checks = Map.of(
                "service", serviceStatus,
                "kafka", kafkaStatus
        );

        HealthResponse response = new HealthResponse(
                "Producer (Cart Service)",
                "readiness",
                overallStatus,
                Instant.now().toString(),
                checks
        );

        HttpStatus httpStatus = isKafkaUp
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Create a new order and publish the full order payload to Kafka.
     * POST /cart-service/create-order
     * Body: { "orderId": "string", "numItems": number }
     */
    @PostMapping("/create-order")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        logger.info("Received create order request: orderId={}, numItems={}",
                request.orderId(), request.numItems());

        Order order = orderService.createOrder(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Order created successfully",
                "order", order
        ));
    }

    /**
     * Update an existing order status and publish the updated full order payload to Kafka.
     * PUT /cart-service/update-order
     * Body: { "orderId": "string", "status": "string" }
     */
    @PutMapping("/update-order")
    public ResponseEntity<Map<String, Object>> updateOrder(@Valid @RequestBody UpdateOrderRequest request) {
        logger.info("Received update order request: orderId={}, status={}",
                request.orderId(), request.status());

        Order order = orderService.updateOrder(request);

        return ResponseEntity.ok(Map.of(
                "message", "Order updated successfully",
                "order", order
        ));
    }
}