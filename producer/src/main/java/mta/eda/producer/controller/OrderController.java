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

        Map<String, Object> response = new HashMap<>();
        response.put("service", "Producer (Cart Service)");
        response.put("version", "0.0.1-SNAPSHOT");
        response.put("timestamp", Instant.now().toString());

        response.put("endpoints", Map.of(
                "health", Map.of(
                        "live", Map.of(
                                "method", "GET",
                                "path", "/cart-service/health/live",
                                "checks", "Service process is running (no Kafka dependency)",
                                "responses", Map.of(
                                        "200", "Service is alive"
                                )
                        ),
                        "ready", Map.of(
                                "method", "GET",
                                "path", "/cart-service/health/ready",
                                "checks", "Kafka reachable + topic exists",
                                "responses", Map.of(
                                        "200", "Kafka is reachable and topic exists",
                                        "503", "Kafka is unreachable or topic missing"
                                )
                        )
                ),
                "orders", Map.of(
                        "createOrder", Map.of(
                                "method", "POST",
                                "path", "/cart-service/create-order",
                                "body", Map.of(
                                        "orderId", "string (required, non-blank)",
                                        "numItems", "int (required, 1-100)"
                                ),
                                "responses", Map.of(
                                        "202", "Order created successfully (published to Kafka)",
                                        "400", "Validation error",
                                        "409", "Duplicate orderId",
                                        "500", "Kafka send failed"
                                )
                        ),
                        "updateOrder", Map.of(
                                "method", "PUT",
                                "path", "/cart-service/update-order",
                                "body", Map.of(
                                        "orderId", "string (required, non-blank)",
                                        "status", "string (required)"
                                ),
                                "responses", Map.of(
                                        "202", "Order updated successfully (published to Kafka)",
                                        "400", "Validation error",
                                        "404", "Order not found",
                                        "500", "Kafka send failed"
                                )
                        )
                )
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Liveness - app is running (does not depend on Kafka).
     * GET /cart-service/health/live
     */
    @GetMapping("/health/live")
    public ResponseEntity<Map<String, Object>> live() {
        Map<String, Object> body = new HashMap<>();
        body.put("service", "Producer (Cart Service)");
        body.put("check", "liveness");
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());

        body.put("components", Map.of(
                "service", Map.of(
                        "status", "UP",
                        "message", "Service is alive"
                )
        ));

        return ResponseEntity.ok(body);
    }

    /**
     * Readiness - checks whether the service can handle requests that depend on Kafka.
     * Returns 200 when Kafka is reachable and the topic exists, otherwise 503.
     * GET /cart-service/health/ready
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        KafkaHealthService.KafkaStatus kafka = kafkaHealthService.readiness();

        HttpStatus httpStatus = kafka.healthy()
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        String overallStatus = kafka.healthy() ? "UP" : "DOWN";

        Map<String, Object> body = new HashMap<>();
        body.put("service", "Producer (Cart Service)");
        body.put("check", "readiness");
        body.put("status", overallStatus);
        body.put("timestamp", Instant.now().toString());

        body.put("components", Map.of(
                "kafka", Map.of(
                        "status", kafka.healthy() ? "UP" : "DOWN",
                        "message", kafka.healthy()
                                ? "Kafka is reachable and topic exists"
                                : kafka.reason()
                )
        ));

        return ResponseEntity.status(httpStatus).body(body);
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

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
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

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "Order updated successfully",
                "order", order
        ));
    }
}