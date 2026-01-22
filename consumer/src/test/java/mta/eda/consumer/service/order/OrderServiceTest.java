package mta.eda.consumer.service.order;

import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.model.order.OrderItem;
import mta.eda.consumer.model.order.ProcessedOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService();
    }

    @Test
    void processOrder_shouldStoreProcessedOrder_whenValidOrder() {
        Order order = new Order("ORD-123", "CUST-1", "2026-01-20", List.of(new OrderItem("ITEM-1", 2, 10.0)), 20.0, "USD", "new", "orders-topic");
        orderService.processOrder(order);
        ProcessedOrder processed = orderService.getProcessedOrder("ORD-0123").orElse(null);
        assertNotNull(processed);
        assertEquals(order, processed.order());
        assertEquals(0.4, processed.shippingCost());
    }

    @Test
    void processOrder_shouldSkipDuplicateOrder() {
        Order order = new Order("ORD-123", "CUST-1", "2026-01-20", List.of(new OrderItem("ITEM-1", 2, 10.0)), 20.0, "USD", "new", "orders-topic");
        orderService.processOrder(order);
        orderService.processOrder(order); // duplicate
        assertEquals(1, orderService.getAllMessages().size());
    }

    @Test
    void processOrder_shouldRejectInvalidTransition() {
        Order order1 = new Order("ORD-123", "CUST-1", "2026-01-20", List.of(new OrderItem("ITEM-1", 2, 10.0)), 20.0, "USD", "new", "orders-topic");
        Order order2 = new Order("ORD-123", "CUST-1", "2026-01-20", List.of(new OrderItem("ITEM-1", 2, 10.0)), 20.0, "USD", "completed", "orders-topic");
        orderService.processOrder(order1);
        orderService.processOrder(order2); // invalid transition
        ProcessedOrder processed = orderService.getProcessedOrder("ORD-0123").orElse(null);
        assertNotNull(processed);
        assertEquals("new", processed.order().status());
    }
}
