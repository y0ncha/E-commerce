package mta.eda.consumer.controller;

import jakarta.validation.Valid;
import mta.eda.consumer.service.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/order-service")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Server metadata endpoint.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> root() {
        /* description of the API endpoint, symetric to producer root **/
        return null;
    }

    /**
     * Liveness probe.
     */
    @GetMapping("/health/live")
    public ResponseEntity<Map<String, Object>> live() {
        return null;
    }

    /**
     * Readiness probe.
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        return null;
    }

    @GetMapping("/order-details")
    public ResponseEntity<Map<String, Object>> getOrderDetails() {
        return null;
    }

    @GetMapping("/getAllOrdersFromTopic")
    public ResponseEntity<Map<String, Object>> getAllOrdersFromTopic() {
        return null;
    }
}
