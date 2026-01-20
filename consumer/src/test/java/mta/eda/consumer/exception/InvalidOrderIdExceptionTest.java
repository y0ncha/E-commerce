package mta.eda.consumer.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvalidOrderIdExceptionTest {
    @Test
    void testExceptionMessage() {
        InvalidOrderIdException ex = new InvalidOrderIdException("ORD-1", "Invalid ID");
        assertEquals("Invalid ID", ex.getMessage());
        assertEquals("ORD-1", ex.getOrderId());
    }
}
