package mta.eda.consumer.exception;

import lombok.Getter;

/**
 * OrderNotFoundException
 * Thrown when a requested order is not found in the consumer's state store.
 */
@Getter
public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order with ID '" + orderId + "' not found in the system.");
        this.orderId = orderId;
    }

}

