package mta.eda.producer.exception;

import lombok.Getter;

/**
 * InvalidStatusTransitionException
 *
 * Thrown when a status transition violates the state machine rules.
 * Example: Trying to transition from DISPATCHED to CONFIRMED (backward)
 */
@Getter
public class InvalidStatusTransitionException extends RuntimeException {

    private final String orderId;
    private final String currentStatus;
    private final String requestedStatus;

    public InvalidStatusTransitionException(String orderId, String currentStatus, String requestedStatus) {
        super(String.format(
            "Invalid status transition for order %s: %s → %s. " +
            "Status must progress sequentially (NEW → CONFIRMED → DISPATCHED → COMPLETED) or to CANCELED from any state.",
            orderId, currentStatus, requestedStatus
        ));
        this.orderId = orderId;
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

}

