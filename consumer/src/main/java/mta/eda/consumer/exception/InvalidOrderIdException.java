package mta.eda.consumer.exception;

import lombok.Getter;

/**
 * InvalidOrderIdException
 * Thrown when an orderId is in an invalid format (not hexadecimal).
 */
@Getter
public class InvalidOrderIdException extends RuntimeException {

    private final String orderId;

    public InvalidOrderIdException(String orderId, String reason) {
        super(reason);
        this.orderId = orderId;
    }

}

