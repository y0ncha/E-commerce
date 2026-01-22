package mta.eda.producer.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvalidOrderIdExceptionTest {
    @Test
    void testExceptionMessageAndOrderId() {
        InvalidOrderIdException ex = new InvalidOrderIdException("BADID");
        assertTrue(ex.getMessage().contains("BADID"));
        assertEquals("BADID", ex.getOrderId());
    }
}
