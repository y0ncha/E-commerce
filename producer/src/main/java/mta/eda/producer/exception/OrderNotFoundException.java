package mta.eda.producer.exception;

/**
 * OrderNotFoundException
 * Thrown when an order is not found in the system.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
    }
}

