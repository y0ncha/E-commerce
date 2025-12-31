package mta.eda.producer.exception;

import lombok.Getter;

/**
 * DuplicateOrderException
 * Thrown when attempting to create an order with an orderId that already exists.
 */
@Getter
public class DuplicateOrderException extends RuntimeException {

    private final String orderId;

    public DuplicateOrderException(String orderId) {
        super("Order already exists: " + orderId);
        this.orderId = orderId;
    }

}
