package mta.eda.consumer.service.util;

import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.model.order.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderUtilsTest {
    @Test
    void calculateShippingCost_shouldReturnTwoPercentOfTotalAmount() {
        Order order = new Order("ORD-1", "CUST-1", "2026-01-20", List.of(new OrderItem("ITEM-1", 1, 100.0)), 100.0, "USD", "new", "orders-topic");
        double shippingCost = OrderUtils.calculateShippingCost(order);
        assertEquals(2.0, shippingCost);
    }

    @Test
    void normalizeOrderId_shouldAddPrefixAndPadHex() {
        String normalized = OrderUtils.normalizeOrderId("A1");
        assertEquals("ORD-00A1", normalized);
    }

    @Test
    void normalizeOrderId_shouldThrowExceptionForNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> OrderUtils.normalizeOrderId(null));
        assertThrows(IllegalArgumentException.class, () -> OrderUtils.normalizeOrderId("   "));
    }
}
