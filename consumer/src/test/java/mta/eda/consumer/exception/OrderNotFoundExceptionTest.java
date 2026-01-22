package mta.eda.consumer.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderNotFoundExceptionTest {
    @Test
    void testExceptionMessage() {
        OrderNotFoundException ex = new OrderNotFoundException("Not found");
        assertEquals("Order with ID 'Not found' not found in the system.", ex.getMessage());
    }
}
