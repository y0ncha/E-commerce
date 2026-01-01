package mta.eda.producer.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler
 * Handles API errors for all controllers using a consistent envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Unified error body builder.
     */
    private Map<String, Object> errorBody(HttpServletRequest request, String error, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", error);
        body.put("message", message);
        body.put("path", request != null ? request.getRequestURI() : "");
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return body;
    }

    /**
     * Handle validation errors (400).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> fieldErrors.put(err.getField(), err.getDefaultMessage()));

        logger.warn("Validation failed: {}", fieldErrors);

        Map<String, Object> details = new HashMap<>();
        details.put("fieldErrors", fieldErrors);
        Map<String, Object> body = errorBody(request, "Bad Request", "Validation error", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle malformed JSON or invalid request body (400).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String raw = ex.getMessage() != null ? ex.getMessage() : "";
        String message = "Invalid request body";
        if (raw.contains("JSON parse error") || raw.contains("Unexpected character")) {
            message = "Malformed JSON syntax";
        } else if (raw.contains("Required request body is missing")) {
            message = "Request body is required";
        } else if (raw.contains("Cannot deserialize value of type") && raw.contains("from String")) {
            message = "Invalid data type: expected number, got text";
        } else if (raw.contains("Unrecognized field")) {
            message = "Request contains unrecognized field";
        }
        Map<String, Object> details = new HashMap<>();
        details.put("cause", raw);
        Map<String, Object> body = errorBody(request, "Bad Request", message, details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle invalid order ID format (400).
     */
    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderId(InvalidOrderIdException ex, HttpServletRequest request) {
        logger.warn("Invalid orderId format: {}", ex.getMessage());
        Map<String, Object> body = errorBody(request, "Bad Request", ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle order not found (404).
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        logger.warn("Order not found: {}", ex.getMessage());
        Map<String, Object> body = errorBody(request, "Not Found", ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handle duplicate order (409).
     */
    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateOrder(DuplicateOrderException ex, HttpServletRequest request) {
        logger.warn("Duplicate order: {}", ex.getMessage());
        Map<String, Object> details = new HashMap<>();
        String msg = ex.getMessage();
        if (msg != null) {
            int idx = msg.indexOf(": ");
            if (idx != -1 && idx + 2 < msg.length()) {
                String orderId = msg.substring(idx + 2).trim();
                details.put("orderId", orderId);
            }
        }
        Map<String, Object> body = errorBody(request, "Conflict", msg != null ? msg : "Order already exists", details);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handle producer send failures (500/503).
     */
    @ExceptionHandler(ProducerSendException.class)
    public ResponseEntity<Map<String, Object>> handleProducerSendException(ProducerSendException ex, HttpServletRequest request) {
        logger.error("Producer send failed: type={}, orderId={}, message={}", ex.getType(), ex.getOrderId(), ex.getMessage());
        
        Map<String, Object> details = new HashMap<>();
        details.put("type", ex.getType());
        details.put("orderId", ex.getOrderId());

        // If circuit breaker is open, return 503 Service Unavailable
        if ("CIRCUIT_BREAKER_OPEN".equals(ex.getType())) {
            Map<String, Object> body = errorBody(request, "Service Unavailable", 
                    "Kafka service is temporarily unavailable (Circuit Breaker Open)", details);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        // Default to 500 Internal Server Error for other send failures
        Map<String, Object> body = errorBody(request, "Internal Server Error", "Failed to publish message", details);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handle all other unhandled exceptions (500).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled error: {}", ex.getMessage(), ex);
        Map<String, Object> details = new HashMap<>();
        details.put("cause", ex.getClass().getSimpleName());
        Map<String, Object> body = errorBody(request, "Internal Server Error", "Internal server error", details);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}