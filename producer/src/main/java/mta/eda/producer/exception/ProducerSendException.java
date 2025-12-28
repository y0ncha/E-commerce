package mta.eda.producer.exception;

import lombok.Getter;

/**
 * ProducerSendException
 * Raised when the producer fails to publish a message to Kafka.
 */
@Getter
public class ProducerSendException extends RuntimeException {

    private final String type;
    private final String orderId;

    public ProducerSendException(String type, String orderId, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.orderId = orderId;
    }
}

