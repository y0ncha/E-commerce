package mta.eda.producer.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderNotFoundExceptionTest {
    @Test
    void testExceptionMessageAndOrderId() {
        OrderNotFoundException ex = new OrderNotFoundException("ORD-404");
        assertTrue(ex.getMessage().contains("ORD-404"));
        assertEquals("ORD-404", ex.getOrderId());
    }
}
