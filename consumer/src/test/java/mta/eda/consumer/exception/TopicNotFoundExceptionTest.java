package mta.eda.consumer.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopicNotFoundExceptionTest {
    @Test
    void testExceptionMessage() {
        TopicNotFoundException ex = new TopicNotFoundException("topic-1", "Topic missing");
        assertEquals("Topic missing", ex.getMessage());
        assertEquals("topic-1", ex.getTopicName());
    }
}
