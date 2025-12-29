package mta.eda.producer.exception;

/**
 * InvalidOrderIdException
 * Thrown when the orderId format is invalid.
 * Expected format: 1 or more hexadecimal characters (0-9, A-F).
 */
public class InvalidOrderIdException extends RuntimeException {

    public InvalidOrderIdException(String orderId) {
        super("Invalid orderId format: '" + orderId + "'. Expected hexadecimal characters (0-9, A-F).");
    }
}

