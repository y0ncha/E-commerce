package mta.eda.producer.exception;

import lombok.Getter;

/**
 * TopicNotFoundException
 * Thrown when attempting to produce a message to a non-existent Kafka topic
 * and auto-topic creation is disabled.
 * Results in an HTTP 500 response with TOPIC_NOT_FOUND error type.
 */
@Getter
public class TopicNotFoundException extends RuntimeException {
    private final String topicName;
    private final String orderId;

    public TopicNotFoundException(String topicName, String orderId, String message, Throwable cause) {
        super(message, cause);
        this.topicName = topicName;
        this.orderId = orderId;
    }
}

