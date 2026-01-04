package mta.eda.producer.service.order;

import mta.eda.producer.exception.InvalidStatusException;
import mta.eda.producer.exception.InvalidStatusTransitionException;
import mta.eda.producer.exception.OrderStatusConflictException;
import mta.eda.producer.model.request.CreateOrderRequest;
import mta.eda.producer.model.request.UpdateOrderRequest;
import mta.eda.producer.service.kafka.KafkaProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for status validation - invalid statuses and invalid transitions
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceStatusValidationTest {

    @Mock
    private KafkaProducerService kafkaProducerService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(kafkaProducerService);
    }

    @Test
    void updateOrder_InvalidStatus_ShouldThrowInvalidStatusException() {
        // Create order
        CreateOrderRequest createRequest = new CreateOrderRequest("1111", 1);
        orderService.createOrder(createRequest);

        // Try to update to invalid status
        UpdateOrderRequest updateRequest = new UpdateOrderRequest("1111", "shipped");

        InvalidStatusException exception = assertThrows(
            InvalidStatusException.class,
            () -> orderService.updateOrder(updateRequest)
        );

        assertTrue(exception.getMessage().contains("Invalid status 'shipped'"));
        assertTrue(exception.getMessage().contains("ORD-1111"));
        assertEquals("shipped", exception.getInvalidStatus());
        assertEquals("ORD-1111", exception.getOrderId());
    }

    @Test
    void updateOrder_InvalidStatus_Various_ShouldThrowInvalidStatusException() {
        // Create order
        CreateOrderRequest createRequest = new CreateOrderRequest("2222", 1);
        orderService.createOrder(createRequest);

        // Test various invalid statuses
        String[] invalidStatuses = {"pending", "processing", "delivered", "invalid_status", "xyz123"};

        for (String invalidStatus : invalidStatuses) {
            UpdateOrderRequest updateRequest = new UpdateOrderRequest("2222", invalidStatus);

            InvalidStatusException exception = assertThrows(
                InvalidStatusException.class,
                () -> orderService.updateOrder(updateRequest),
                "Should throw InvalidStatusException for status: " + invalidStatus
            );

            assertTrue(exception.getMessage().contains("Invalid status '" + invalidStatus + "'"));
        }
    }

    @Test
    void updateOrder_SkippingStates_ShouldThrowInvalidStatusTransitionException() {
        // Create order (status: new)
        CreateOrderRequest createRequest = new CreateOrderRequest("3333", 1);
        orderService.createOrder(createRequest);

        // Try to skip to dispatched (skipping confirmed)
        UpdateOrderRequest updateRequest = new UpdateOrderRequest("3333", "dispatched");

        InvalidStatusTransitionException exception = assertThrows(
            InvalidStatusTransitionException.class,
            () -> orderService.updateOrder(updateRequest)
        );

        assertTrue(exception.getMessage().contains("Invalid status transition"));
        assertTrue(exception.getMessage().contains("new"));
        assertTrue(exception.getMessage().contains("dispatched"));
        assertTrue(exception.getMessage().contains("sequentially"));
        assertEquals("new", exception.getCurrentStatus());
        assertEquals("dispatched", exception.getRequestedStatus());
        assertEquals("ORD-3333", exception.getOrderId());
    }

    @Test
    void updateOrder_SkippingMultipleStates_ShouldThrowInvalidStatusTransitionException() {
        // Create order (status: new)
        CreateOrderRequest createRequest = new CreateOrderRequest("4444", 1);
        orderService.createOrder(createRequest);

        // Try to skip to completed (skipping confirmed and dispatched)
        UpdateOrderRequest updateRequest = new UpdateOrderRequest("4444", "completed");

        InvalidStatusTransitionException exception = assertThrows(
            InvalidStatusTransitionException.class,
            () -> orderService.updateOrder(updateRequest)
        );

        assertTrue(exception.getMessage().contains("Invalid status transition"));
        assertEquals("new", exception.getCurrentStatus());
        assertEquals("completed", exception.getRequestedStatus());
    }

    @Test
    void updateOrder_BackwardTransition_ShouldThrowInvalidStatusTransitionException() {
        // Create and progress order to confirmed
        CreateOrderRequest createRequest = new CreateOrderRequest("5555", 1);
        orderService.createOrder(createRequest);

        UpdateOrderRequest toConfirmed = new UpdateOrderRequest("5555", "confirmed");
        orderService.updateOrder(toConfirmed);

        // Try to go backward to new
        UpdateOrderRequest backward = new UpdateOrderRequest("5555", "new");

        InvalidStatusTransitionException exception = assertThrows(
            InvalidStatusTransitionException.class,
            () -> orderService.updateOrder(backward)
        );

        assertTrue(exception.getMessage().contains("Invalid status transition"));
        assertEquals("confirmed", exception.getCurrentStatus());
        assertEquals("new", exception.getRequestedStatus());
    }

    @Test
    void updateOrder_ValidSequentialTransitions_ShouldSucceed() {
        // Create order (status: new)
        CreateOrderRequest createRequest = new CreateOrderRequest("6666", 1);
        orderService.createOrder(createRequest);

        // Valid transition: new → confirmed
        UpdateOrderRequest toConfirmed = new UpdateOrderRequest("6666", "confirmed");
        assertDoesNotThrow(() -> orderService.updateOrder(toConfirmed));

        // Valid transition: confirmed → dispatched
        UpdateOrderRequest toDispatched = new UpdateOrderRequest("6666", "dispatched");
        assertDoesNotThrow(() -> orderService.updateOrder(toDispatched));

        // Valid transition: dispatched → completed
        UpdateOrderRequest toCompleted = new UpdateOrderRequest("6666", "completed");
        assertDoesNotThrow(() -> orderService.updateOrder(toCompleted));
    }

    @Test
    void updateOrder_CancelFromAnyState_ShouldSucceed() {
        // Test canceling from new
        CreateOrderRequest create1 = new CreateOrderRequest("7777", 1);
        orderService.createOrder(create1);
        UpdateOrderRequest cancel1 = new UpdateOrderRequest("7777", "canceled");
        assertDoesNotThrow(() -> orderService.updateOrder(cancel1));

        // Test canceling from confirmed
        CreateOrderRequest create2 = new CreateOrderRequest("8888", 1);
        orderService.createOrder(create2);
        orderService.updateOrder(new UpdateOrderRequest("8888", "confirmed"));
        UpdateOrderRequest cancel2 = new UpdateOrderRequest("8888", "canceled");
        assertDoesNotThrow(() -> orderService.updateOrder(cancel2));

        // Test canceling from dispatched
        CreateOrderRequest create3 = new CreateOrderRequest("9999", 1);
        orderService.createOrder(create3);
        orderService.updateOrder(new UpdateOrderRequest("9999", "confirmed"));
        orderService.updateOrder(new UpdateOrderRequest("9999", "dispatched"));
        UpdateOrderRequest cancel3 = new UpdateOrderRequest("9999", "canceled");
        assertDoesNotThrow(() -> orderService.updateOrder(cancel3));
    }

    @Test
    void updateOrder_ConfirmedToCompleted_ShouldThrowException() {
        // Create and progress to confirmed
        CreateOrderRequest createRequest = new CreateOrderRequest("AAAA", 1);
        orderService.createOrder(createRequest);
        orderService.updateOrder(new UpdateOrderRequest("AAAA", "confirmed"));

        // Try to skip dispatched
        UpdateOrderRequest toCompleted = new UpdateOrderRequest("AAAA", "completed");

        InvalidStatusTransitionException exception = assertThrows(
            InvalidStatusTransitionException.class,
            () -> orderService.updateOrder(toCompleted)
        );

        assertEquals("confirmed", exception.getCurrentStatus());
        assertEquals("completed", exception.getRequestedStatus());
    }

    @Test
    void updateOrder_SameStatus_ShouldThrowOrderStatusConflictException() {
        // Create order (status: new)
        CreateOrderRequest createRequest = new CreateOrderRequest("BBBB", 1);
        orderService.createOrder(createRequest);

        // Try to update to the same status (new)
        UpdateOrderRequest sameStatus = new UpdateOrderRequest("BBBB", "new");

        OrderStatusConflictException exception = assertThrows(
            OrderStatusConflictException.class,
            () -> orderService.updateOrder(sameStatus)
        );

        assertTrue(exception.getMessage().contains("already in status 'new'"));
        assertEquals("ORD-BBBB", exception.getOrderId());
        assertEquals("new", exception.getCurrentStatus());
    }

    @Test
    void updateOrder_SameStatusAfterTransition_ShouldThrowOrderStatusConflictException() {
        // Create order and transition to confirmed
        CreateOrderRequest createRequest = new CreateOrderRequest("CCCC", 1);
        orderService.createOrder(createRequest);
        orderService.updateOrder(new UpdateOrderRequest("CCCC", "confirmed"));

        // Try to update to the same status (confirmed)
        UpdateOrderRequest sameStatus = new UpdateOrderRequest("CCCC", "confirmed");

        OrderStatusConflictException exception = assertThrows(
            OrderStatusConflictException.class,
            () -> orderService.updateOrder(sameStatus)
        );

        assertTrue(exception.getMessage().contains("already in status 'confirmed'"));
        assertEquals("ORD-CCCC", exception.getOrderId());
        assertEquals("confirmed", exception.getCurrentStatus());
    }
}



