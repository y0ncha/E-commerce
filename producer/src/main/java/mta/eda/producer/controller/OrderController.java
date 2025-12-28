package mta.eda.producer.controller;

import jakarta.validation.Valid;
import mta.eda.producer.model.CreateOrderRequest;
import mta.eda.producer.model.Order;
import mta.eda.producer.model.UpdateOrderRequest;
import mta.eda.producer.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /**
     * Constructor for OrderController
     * @param orderService the order service
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
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

        return ResponseEntity.ok(Map.of(
                "message", "Order updated successfully",
                "order", order
        ));
    }
}
