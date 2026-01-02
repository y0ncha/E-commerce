package mta.eda.producer.exception;

import lombok.Getter;

/**
 * InvalidOrderIdException
 * Thrown when the orderId format is invalid.
 * Expected format: 1 or more hexadecimal characters (0-9, A-F).
 */
@Getter
public class InvalidOrderIdException extends RuntimeException {

    private final String orderId;

    public InvalidOrderIdException(String orderId) {
        super("Invalid orderId format: '" + orderId + "'. Expected hexadecimal characters (0-9, A-F).");
        this.orderId = orderId;
    }
}

