package mta.eda.producer.exception;

import lombok.Getter;

/**
 * ServiceUnavailableException
 * Thrown when a downstream service (like Kafka) is unavailable or the circuit breaker is open.
 * Results in an HTTP 503 response.
 */
@Getter
public class ServiceUnavailableException extends RuntimeException {
    private final String type;
    private final String orderId;

    public ServiceUnavailableException(String type, String orderId, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.orderId = orderId;
    }

}
