package mta.eda.producer.controller;

import jakarta.validation.Valid;
import mta.eda.producer.model.CreateOrderRequest;
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
import java.util.HashMap;
import java.util.Map;

/**
 * OrderController
 * REST API endpoints for order operations.
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final KafkaHealthService kafkaHealthService;

    /**
     * Constructor for OrderController
     * @param orderService the order service
     * @param kafkaHealthService the Kafka health service (readiness checks)
     */
    public OrderController(OrderService orderService, KafkaHealthService kafkaHealthService) {
        this.orderService = orderService;
        this.kafkaHealthService = kafkaHealthService;
    }

    /**
     * Info endpoint - provides service metadata + readiness/liveness snapshot.
     * Always returns 200 (use /health/ready for a 200/503 readiness gate).
     * GET /api
     * GET /api/
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> info() {
        logger.debug("Info endpoint accessed");

        KafkaHealthService.KafkaStatus readiness = kafkaHealthService.readiness();
        boolean kafkaHealthy = readiness.healthy();

        Map<String, Object> response = new HashMap<>();
        response.put("service", "Producer (Cart Service)");
        response.put("timestamp", Instant.now().toString());

        response.put("liveness", Map.of(
                "status", "UP",
                "message", "Producer service is running"
        ));

        response.put("readiness", Map.of(
                "status", kafkaHealthy ? "UP" : "DOWN",
                "message", kafkaHealthy
                        ? "Kafka is reachable and topic exists"
                        : readiness.reason()
        ));

        response.put("endpoints", Map.of(
                "createOrder", "POST /api/create-order",
                "updateOrder", "PUT /api/update-order",
                "liveness", "GET /api/health/live",
                "readiness", "GET /api/health/ready"
        ));

        response.put("status", kafkaHealthy ? "UP" : "DEGRADED");

        return ResponseEntity.ok(response);
    }

    /**
     * Liveness - app is running (does not depend on Kafka).
     * GET /api/health/live
     */
    @GetMapping("/health/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(Map.of(
                "service", "Producer (Cart Service)",
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "message", "Service is alive"
        ));
    }

    /**
     * Readiness - app can serve requests that require Kafka.
     * Returns 200 when ready, 503 when not ready.
     * GET /api/health/ready
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        KafkaHealthService.KafkaStatus readiness = kafkaHealthService.readiness();

        HttpStatus status = readiness.healthy()
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(Map.of(
                "service", "Producer (Cart Service)",
                "status", readiness.healthy() ? "UP" : "DOWN",
                "timestamp", Instant.now().toString(),
                "message", readiness.healthy()
                        ? "Kafka is reachable and topic exists"
                        : readiness.reason()
        ));
    }

    /**
     * Create a new order and publish the full order payload to Kafka.
     * POST /api/create-order
     * Body: { "orderId": "string", "numItems": number }
     */
    @PostMapping("/create-order")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        logger.info("Received create order request: orderId={}, numItems={}",
                request.orderId(), request.numItems());

        Order order = orderService.createOrder(request);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "Order created successfully",
                "order", order
        ));
    }

    /**
     * Update an existing order status and publish the updated full order payload to Kafka.
     * PUT /api/update-order
     * Body: { "orderId": "string", "status": "string" }
     */
    @PutMapping("/update-order")
    public ResponseEntity<Map<String, Object>> updateOrder(@Valid @RequestBody UpdateOrderRequest request) {
        logger.info("Received update order request: orderId={}, status={}",
                request.orderId(), request.status());

        Order order = orderService.updateOrder(request);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "Order updated successfully",
                "order", order
        ));
    }
}