package mta.eda.producer.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler
 * Handles API errors for all controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle validation errors (400).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage())
        );

        logger.warn("Validation failed: {}", fieldErrors);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Validation error");
        body.put("errors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle malformed JSON or invalid request body (400).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMalformedJson(HttpMessageNotReadableException ex) {
        String errorMsg = ex.getMessage() != null ? ex.getMessage() : "";
        logger.warn("Malformed JSON or invalid request body: {}", errorMsg);

        String message = "Invalid request body";

        // Try to extract more specific error message
        if (errorMsg.contains("JSON parse error") || errorMsg.contains("Unexpected character")) {
            message = "Malformed JSON syntax";
        } else if (errorMsg.contains("Required request body is missing")) {
            message = "Request body is required";
        } else if (errorMsg.contains("Cannot deserialize value of type") && errorMsg.contains("from String")) {
            // e.g., trying to put string "abc" into Integer field
            message = "Invalid data type: expected number, got text";
        } else if (errorMsg.contains("Cannot deserialize")) {
            message = "Invalid data format in request body";
        } else if (errorMsg.contains("Unrecognized field")) {
            // Extract field name if possible
            int start = errorMsg.indexOf("\"");
            int end = errorMsg.indexOf("\"", start + 1);
            if (start != -1 && end != -1) {
                String fieldName = errorMsg.substring(start + 1, end);
                message = "Unknown field: '" + fieldName + "'";
            } else {
                message = "Request contains unrecognized field";
            }
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message));
    }

    /**
     * Handle invalid order ID format (400).
     */
    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<Map<String, String>> handleInvalidOrderId(InvalidOrderIdException ex) {
        logger.warn("Invalid orderId format: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    /**
     * Handle order not found (404).
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleOrderNotFound(OrderNotFoundException ex) {
        logger.warn("Order not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", ex.getMessage()));
    }

    /**
     * Handle duplicate order (409).
     */
    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateOrder(DuplicateOrderException ex) {
        logger.warn("Duplicate order: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", ex.getMessage()));
    }

    /**
     * Handle producer send failures (500) with type and orderId.
     */
    @ExceptionHandler(ProducerSendException.class)
    public ResponseEntity<Map<String, Object>> handleProducerSendException(ProducerSendException ex) {
        logger.error("Producer send failed: type={}, orderId={}, message={}",
                ex.getType(), ex.getOrderId(), ex.getMessage(), ex);

        Map<String, Object> body = new HashMap<>();
        body.put("error", "Failed to publish message");
        body.put("type", ex.getType());
        body.put("orderId", ex.getOrderId());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handle all other unhandled exceptions (500).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnhandled(Exception ex) {
        logger.error("Unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Internal server error"));
    }
}