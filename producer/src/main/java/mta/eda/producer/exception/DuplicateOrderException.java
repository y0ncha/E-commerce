package mta.eda.producer.exception;

/**
 * DuplicateOrderException
 * Thrown when attempting to create an order with an orderId that already exists.
 */
public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException(String orderId) {
        super("Order already exists: " + orderId);
    }
}

