package mta.eda.producer.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopicNotFoundExceptionTest {
    @Test
    void testExceptionFieldsAndMessage() {
        Throwable cause = new RuntimeException("Kafka error");
        TopicNotFoundException ex = new TopicNotFoundException("topic-x", "ORD-123", "Topic not found", cause);
        assertEquals("topic-x", ex.getTopicName());
        assertEquals("ORD-123", ex.getOrderId());
        assertEquals("Topic not found", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}
