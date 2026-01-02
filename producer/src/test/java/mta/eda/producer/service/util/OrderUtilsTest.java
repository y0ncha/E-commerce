package mta.eda.producer.service.util;

import mta.eda.producer.model.order.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderUtilsTest {

    @Test
    void testGenerateOrderItems() {
        int count = 5;
        List<OrderItem> items = OrderUtils.generateOrderItems(count);
        
        assertEquals(count, items.size(), "Should generate correct number of items");
        for (OrderItem item : items) {
            assertNotNull(item.itemId(), "Item ID should not be null");
            assertTrue(item.itemId().startsWith("ITEM-"), "Item ID should start with ITEM-");
            assertTrue(item.quantity() >= 1 && item.quantity() <= 10, "Quantity should be between 1 and 10");
            assertTrue(item.price() >= 1.00 && item.price() <= 101.00, "Price should be within range");
        }
    }

    @Test
    void testCalculateTotalAmount() {
        List<OrderItem> items = Arrays.asList(
            new OrderItem("1", 2, 10.00), // 20.00
            new OrderItem("2", 1, 5.50)   // 5.50
        );
        
        double total = OrderUtils.calculateTotalAmount(items);
        assertEquals(25.50, total, 0.001, "Total amount should be sum of price * quantity");
    }

    @Test
    void testRoundToTwoDecimals() {
        assertEquals(10.56, OrderUtils.roundToTwoDecimals(10.555), "Should round up (half up)");
        assertEquals(10.55, OrderUtils.roundToTwoDecimals(10.554), "Should round down");
        assertEquals(10.00, OrderUtils.roundToTwoDecimals(10.001), "Should round to 2 decimals");
    }
}
