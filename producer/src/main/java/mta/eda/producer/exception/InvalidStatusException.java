package mta.eda.producer.exception;

import lombok.Getter;

/**
 * InvalidStatusException
 *
 * Thrown when an invalid or unknown status value is provided.
 * Example: Trying to create an order with status "invalid_status"
 */
@Getter
public class InvalidStatusException extends RuntimeException {

    private final String orderId;
    private final String invalidStatus;

    public InvalidStatusException(String orderId, String invalidStatus) {
        super(String.format(
            "Invalid status '%s' for order %s. Valid statuses are: new, confirmed, dispatched, completed, canceled.",
            invalidStatus, orderId
        ));
        this.orderId = orderId;
        this.invalidStatus = invalidStatus;
    }

    public InvalidStatusException(String invalidStatus) {
        super(String.format(
            "Invalid status '%s'. Valid statuses are: new, confirmed, dispatched, completed, canceled.",
            invalidStatus
        ));
        this.orderId = null;
        this.invalidStatus = invalidStatus;
    }
}

