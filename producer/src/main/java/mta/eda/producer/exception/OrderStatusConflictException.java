package mta.eda.producer.exception;

import lombok.Getter;

/**
 * OrderStatusConflictException
 *
 * Thrown when attempting to update an order to a status it already has.
 * This represents a conflict - the order is already in the requested state.
 */
@Getter
public class OrderStatusConflictException extends RuntimeException {

    private final String orderId;
    private final String currentStatus;

    public OrderStatusConflictException(String orderId, String currentStatus) {
        super(String.format(
            "Order %s is already in status '%s'. No update needed.",
            orderId, currentStatus
        ));
        this.orderId = orderId;
        this.currentStatus = currentStatus;
    }
}

