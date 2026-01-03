package mta.eda.producer.controller;

import jakarta.validation.Valid;
import mta.eda.producer.model.request.CreateOrderRequest;
import mta.eda.producer.model.response.HealthCheck;
import mta.eda.producer.model.response.HealthResponse;
import mta.eda.producer.model.order.Order;
import mta.eda.producer.model.request.UpdateOrderRequest;
import mta.eda.producer.service.general.HealthService;
import mta.eda.producer.service.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final HealthService healthService;

    public OrderController(OrderService orderService, HealthService healthService) {
        this.orderService = orderService;
        this.healthService = healthService;
    }

    /**
     * Server metadata endpoint.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> root() {
        logger.debug("Root endpoint accessed");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "Cart Service (Producer)");
        response.put("timestamp", Instant.now().toString());

        Map<String, Object> endpoints = new LinkedHashMap<>();

        // Health endpoints
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("live", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/cart-service/health/live");
            put("description", "Liveness probe");
        }});
        health.put("ready", new LinkedHashMap<String, Object>() {{
            put("method", "GET");
            put("path", "/cart-service/health/ready");
            put("description", "Readiness probe");
        }});

        // Order endpoints
        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("createOrder", new LinkedHashMap<String, Object>() {{
            put("method", "POST");
            put("path", "/cart-service/create-order");
            put("description", "Create new order");
        }});
        orders.put("updateOrder", new LinkedHashMap<String, Object>() {{
            put("method", "PUT");
            put("path", "/cart-service/update-order");
            put("description", "Update existing order");
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
        HealthResponse response = new HealthResponse("Producer (Cart Service)", "liveness", "UP", Instant.now().toString(), checks);
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
        boolean isKafkaUp = "UP".equals(kafkaStatus.status());
        String overallStatus = isKafkaUp ? "UP" : "DOWN";
        Map<String, HealthCheck> checks = Map.of("service", serviceStatus, "kafka", kafkaStatus);
        HealthResponse response = new HealthResponse("Producer (Cart Service)", "readiness", overallStatus, Instant.now().toString(), checks);
        HttpStatus httpStatus = isKafkaUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Create a new order.
     */
    @PostMapping("/create-order")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        logger.info("Received create order request: orderId={}, numItems={}", request.orderId(), request.numItems());

        Order order = orderService.createOrder(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Order created successfully",
                "order", order
        ));
    }

    /**
     * Update an existing order.
     */
    @PutMapping("/update-order")
    public ResponseEntity<Map<String, Object>> updateOrder(@Valid @RequestBody UpdateOrderRequest request) {
        logger.info("Received update order request: orderId={}, status={}", request.orderId(), request.status());

        Order order = orderService.updateOrder(request);

        return ResponseEntity.ok(Map.of(
                "message", "Order updated successfully",
                "order", order
        ));
    }
}
