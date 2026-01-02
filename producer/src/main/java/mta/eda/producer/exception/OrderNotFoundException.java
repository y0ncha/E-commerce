package mta.eda.producer.exception;

import lombok.Getter;

/**
 * OrderNotFoundException
 * Thrown when an order is not found in the system.
 */
@Getter
public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }
}

