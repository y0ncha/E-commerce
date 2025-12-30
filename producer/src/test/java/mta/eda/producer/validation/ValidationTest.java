package mta.eda.producer.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import mta.eda.producer.model.CreateOrderRequest;
import mta.eda.producer.model.Order;
import mta.eda.producer.model.OrderItem;
import mta.eda.producer.model.UpdateOrderRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validation tests for all model classes.
 * Tests type validation and constraint validation for:
 * - CreateOrderRequest
 * - UpdateOrderRequest
 * - Order
 * - OrderItem
 */
class ValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ==================== CreateOrderRequest Tests ====================

    @Test
    void testCreateOrderRequest_Valid() {
        CreateOrderRequest request = new CreateOrderRequest("ABC123", 5);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Valid CreateOrderRequest should have no violations");
    }

    @Test
    void testCreateOrderRequest_BlankOrderId() {
        CreateOrderRequest request = new CreateOrderRequest("", 5);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Blank orderId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderId")));
    }

    @Test
    void testCreateOrderRequest_NullOrderId() {
        CreateOrderRequest request = new CreateOrderRequest(null, 5);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Null orderId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderId")));
    }

    @Test
    void testCreateOrderRequest_NullNumItems() {
        CreateOrderRequest request = new CreateOrderRequest("ABC123", null);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Null numItems should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("numItems")));
    }

    @Test
    void testCreateOrderRequest_NumItemsTooSmall() {
        CreateOrderRequest request = new CreateOrderRequest("ABC123", 0);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "numItems=0 should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("numItems")));
    }

    @Test
    void testCreateOrderRequest_NumItemsTooLarge() {
        CreateOrderRequest request = new CreateOrderRequest("ABC123", 101);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "numItems=101 should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("numItems")));
    }

    @Test
    void testCreateOrderRequest_NumItemsMinBoundary() {
        CreateOrderRequest request = new CreateOrderRequest("ABC123", 1);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "numItems=1 should be valid");
    }

    @Test
    void testCreateOrderRequest_NumItemsMaxBoundary() {
        CreateOrderRequest request = new CreateOrderRequest("ABC123", 100);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "numItems=100 should be valid");
    }

    // ==================== UpdateOrderRequest Tests ====================

    @Test
    void testUpdateOrderRequest_Valid() {
        UpdateOrderRequest request = new UpdateOrderRequest("ABC123", "shipped");
        Set<ConstraintViolation<UpdateOrderRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Valid UpdateOrderRequest should have no violations");
    }

    @Test
    void testUpdateOrderRequest_BlankOrderId() {
        UpdateOrderRequest request = new UpdateOrderRequest("", "shipped");
        Set<ConstraintViolation<UpdateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Blank orderId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderId")));
    }

    @Test
    void testUpdateOrderRequest_NullOrderId() {
        UpdateOrderRequest request = new UpdateOrderRequest(null, "shipped");
        Set<ConstraintViolation<UpdateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Null orderId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderId")));
    }

    @Test
    void testUpdateOrderRequest_BlankStatus() {
        UpdateOrderRequest request = new UpdateOrderRequest("ABC123", "");
        Set<ConstraintViolation<UpdateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Blank status should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("status")));
    }

    @Test
    void testUpdateOrderRequest_NullStatus() {
        UpdateOrderRequest request = new UpdateOrderRequest("ABC123", null);
        Set<ConstraintViolation<UpdateOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Null status should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("status")));
    }

    // ==================== OrderItem Tests ====================

    @Test
    void testOrderItem_Valid() {
        OrderItem item = new OrderItem("ITEM-0001", 5, 19.99);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertTrue(violations.isEmpty(), "Valid OrderItem should have no violations");
    }

    @Test
    void testOrderItem_BlankItemId() {
        OrderItem item = new OrderItem("", 5, 19.99);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertFalse(violations.isEmpty(), "Blank itemId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("itemId")));
    }

    @Test
    void testOrderItem_NullItemId() {
        OrderItem item = new OrderItem(null, 5, 19.99);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertFalse(violations.isEmpty(), "Null itemId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("itemId")));
    }

    @Test
    void testOrderItem_ZeroQuantity() {
        OrderItem item = new OrderItem("ITEM-0001", 0, 19.99);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertFalse(violations.isEmpty(), "Zero quantity should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("quantity")));
    }

    @Test
    void testOrderItem_NegativeQuantity() {
        OrderItem item = new OrderItem("ITEM-0001", -1, 19.99);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertFalse(violations.isEmpty(), "Negative quantity should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("quantity")));
    }

    @Test
    void testOrderItem_ZeroPrice() {
        OrderItem item = new OrderItem("ITEM-0001", 5, 0.0);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertFalse(violations.isEmpty(), "Zero price should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("price")));
    }

    @Test
    void testOrderItem_NegativePrice() {
        OrderItem item = new OrderItem("ITEM-0001", 5, -19.99);
        Set<ConstraintViolation<OrderItem>> violations = validator.validate(item);
        assertFalse(violations.isEmpty(), "Negative price should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("price")));
    }

    // ==================== Order Tests ====================

    @Test
    void testOrder_Valid() {
        List<OrderItem> items = List.of(
                new OrderItem("ITEM-0001", 2, 19.99),
                new OrderItem("ITEM-0002", 1, 9.99)
        );
        Order order = new Order(
                "ORD-0001",
                "CUST-0001",
                "2025-12-30T00:00:00Z",
                items,
                49.97,
                "USD",
                "new"
        );
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertTrue(violations.isEmpty(), "Valid Order should have no violations");
    }

    @Test
    void testOrder_BlankOrderId() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("", "CUST-0001", "2025-12-30T00:00:00Z", items, 39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Blank orderId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderId")));
    }

    @Test
    void testOrder_NullOrderId() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order(null, "CUST-0001", "2025-12-30T00:00:00Z", items, 39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Null orderId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderId")));
    }

    @Test
    void testOrder_BlankCustomerId() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "", "2025-12-30T00:00:00Z", items, 39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Blank customerId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("customerId")));
    }

    @Test
    void testOrder_NullCustomerId() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", null, "2025-12-30T00:00:00Z", items, 39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Null customerId should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("customerId")));
    }

    @Test
    void testOrder_BlankOrderDate() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "", items, 39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Blank orderDate should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderDate")));
    }

    @Test
    void testOrder_NullOrderDate() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", null, items, 39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Null orderDate should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderDate")));
    }

    @Test
    void testOrder_NullItems() {
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", null, 0.0, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Null items should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("items")));
    }

    @Test
    void testOrder_EmptyItems() {
        List<OrderItem> items = new ArrayList<>();
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 0.0, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Empty items list should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("items")));
    }

    @Test
    void testOrder_InvalidItemInList() {
        List<OrderItem> items = List.of(new OrderItem("", 0, -1.0)); // Invalid item
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 0.0, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Invalid OrderItem should cause validation error");
        // Should have violations for itemId, quantity, and price
        assertTrue(violations.size() >= 3, "Should have at least 3 violations for invalid OrderItem");
    }

    @Test
    void testOrder_NegativeTotalAmount() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, -39.98, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Negative totalAmount should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("totalAmount")));
    }

    @Test
    void testOrder_ZeroTotalAmount() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 0.0, "USD", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertTrue(violations.isEmpty(), "Zero totalAmount should be valid (PositiveOrZero)");
    }

    @Test
    void testOrder_BlankCurrency() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 39.98, "", "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Blank currency should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("currency")));
    }

    @Test
    void testOrder_NullCurrency() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 39.98, null, "new");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Null currency should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("currency")));
    }

    @Test
    void testOrder_BlankStatus() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 39.98, "USD", "");
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Blank status should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("status")));
    }

    @Test
    void testOrder_NullStatus() {
        List<OrderItem> items = List.of(new OrderItem("ITEM-0001", 2, 19.99));
        Order order = new Order("ORD-0001", "CUST-0001", "2025-12-30T00:00:00Z", items, 39.98, "USD", null);
        Set<ConstraintViolation<Order>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Null status should cause validation error");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("status")));
    }
}
