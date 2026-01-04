package mta.eda.producer.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
 * Aligned with MTA EDA Exercise 2 requirements.
 *
 * Error Types:
 * - VALIDATION_ERROR: Request validation failed
 * - MALFORMED_JSON: Invalid JSON in request body
 * - INVALID_ORDER_ID: Invalid order ID format
 * - INVALID_STATUS: Invalid or unknown status value
 * - INVALID_STATUS_TRANSITION: Status transition violates state machine rules
 * - ORDER_NOT_FOUND: Order not found in state store
 * - DUPLICATE_ORDER: Attempted to create a duplicate order
 * - ORDER_STATUS_CONFLICT: Attempted to update order to status it already has
 * - KAFKA_SEND_FAILURE: Failed to send message to Kafka after retries
 * - TOPIC_NOT_FOUND: Required Kafka topic does not exist
 * - CIRCUIT_BREAKER_OPEN: Circuit breaker is open due to high failure rates
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        logger.warn("Validation error: {}", ex.getMessage());
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> fieldErrors.put(err.getField(), err.getDefaultMessage()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "VALIDATION_ERROR");
        details.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", "Validation error", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        logger.warn("Malformed JSON received: {}", ex.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "MALFORMED_JSON");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", "Invalid request body. Ensure JSON is properly formatted.", details));
    }

    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderId(InvalidOrderIdException ex, HttpServletRequest request) {
        logger.warn("Invalid orderId format: {}", ex.getOrderId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "INVALID_ORDER_ID");
        details.put("orderId", ex.getOrderId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", ex.getMessage(), details));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        logger.info("Order not found: {}", ex.getOrderId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "ORDER_NOT_FOUND");
        details.put("orderId", ex.getOrderId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(request, "Not Found", ex.getMessage(), details));
    }

    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateOrder(DuplicateOrderException ex, HttpServletRequest request) {
        logger.warn("Duplicate order attempt: {}", ex.getOrderId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "DUPLICATE_ORDER");
        details.put("orderId", ex.getOrderId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(request, "Conflict", ex.getMessage(), details));
    }

    /**
     * Handle Order Status Conflict (409 Conflict).
     * Triggered when attempting to update an order to a status it already has.
     * Example: Order is in "confirmed" status, trying to update it to "confirmed"
     */
    @ExceptionHandler(OrderStatusConflictException.class)
    public ResponseEntity<Map<String, Object>> handleOrderStatusConflict(OrderStatusConflictException ex, HttpServletRequest request) {
        logger.info("Status conflict for order {}: already in status '{}'", ex.getOrderId(), ex.getCurrentStatus());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "ORDER_STATUS_CONFLICT");
        details.put("orderId", ex.getOrderId());
        details.put("currentStatus", ex.getCurrentStatus());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(request, "Conflict", ex.getMessage(), details));
    }

    /**
     * Handle Invalid Status Transition (400 Bad Request).
     * Triggered when attempting to transition to an invalid state according to the state machine.
     * Example: NEW → DISPATCHED (skipping CONFIRMED)
     */
    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStatusTransition(InvalidStatusTransitionException ex, HttpServletRequest request) {
        logger.warn("Invalid status transition for order {}: {} → {}",
                ex.getOrderId(), ex.getCurrentStatus(), ex.getRequestedStatus());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "INVALID_STATUS_TRANSITION");
        details.put("orderId", ex.getOrderId());
        details.put("currentStatus", ex.getCurrentStatus());
        details.put("requestedStatus", ex.getRequestedStatus());
        details.put("validTransitions", getValidTransitions(ex.getCurrentStatus()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", ex.getMessage(), details));
    }

    /**
     * Handle Invalid Status Value (400 Bad Request).
     * Triggered when an unknown or invalid status value is provided.
     * Example: "invalid_status", "pending", etc.
     */
    @ExceptionHandler(InvalidStatusException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStatus(InvalidStatusException ex, HttpServletRequest request) {
        logger.warn("Invalid status value: {}", ex.getInvalidStatus());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "INVALID_STATUS");
        if (ex.getOrderId() != null) {
            details.put("orderId", ex.getOrderId());
        }
        details.put("invalidStatus", ex.getInvalidStatus());
        details.put("validStatuses", new String[]{"new", "confirmed", "dispatched", "completed", "canceled"});
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", ex.getMessage(), details));
    }

    /**
     * Helper method to get valid transitions for a given status.
     */
    private String[] getValidTransitions(String currentStatus) {
        if (currentStatus == null) {
            return new String[]{"new", "confirmed", "dispatched", "completed", "canceled"};
        }
        return switch (currentStatus.toLowerCase()) {
            case "new" -> new String[]{"confirmed", "canceled"};
            case "confirmed" -> new String[]{"dispatched", "canceled"};
            case "dispatched" -> new String[]{"completed", "canceled"};
            case "completed", "canceled" -> new String[]{}; // Terminal states
            default -> new String[]{"canceled"};
        };
    }

    /**
     * Handle Active Request Failures (500 Internal Server Error).
     * Triggered when a producer send fails after retries.
     * Architectural Reasoning: A failed send is an unexpected server condition during request processing.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleProducerFailure(ServiceUnavailableException ex, HttpServletRequest request) {
        logger.error("Producer send failure for orderId={}: {}", ex.getOrderId(), ex.getMessage());
        
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", ex.getType());
        details.put("orderId", ex.getOrderId());

        Map<String, Object> body = errorBody(request, "Internal Server Error", 
                "The server encountered an error while publishing the order event.", details);
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handle Topic Not Found (500 Internal Server Error).
     * Triggered when attempting to produce to a non-existent Kafka topic.
     * Architectural Reasoning: This is a configuration error that prevents message delivery.
     */
    @ExceptionHandler(TopicNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTopicNotFound(TopicNotFoundException ex, HttpServletRequest request) {
        logger.error("Topic not found for orderId={}: Topic '{}' does not exist", ex.getOrderId(), ex.getTopicName());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "TOPIC_NOT_FOUND");
        details.put("orderId", ex.getOrderId());
        details.put("topicName", ex.getTopicName());

        Map<String, Object> body = errorBody(request, "Internal Server Error",
                "The configured Kafka topic does not exist and auto-creation is disabled.", details);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handle Fail-Fast Circuit Breaker (503 Service Unavailable).
     * Triggered when the circuit is OPEN.
     * Architectural Reasoning: 503 indicates a temporary state where the service is protecting itself.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleCircuitBreakerOpen(CallNotPermittedException ex, HttpServletRequest request) {
        logger.warn("Circuit Breaker is OPEN - rejecting request");
        
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "CIRCUIT_BREAKER_OPEN");

        Map<String, Object> body = errorBody(request, "Service Unavailable", 
                "The service is temporarily unavailable due to high failure rates. Please try again later.", details);
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Handle unknown endpoints (404 Not Found).
     */
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandlerFound(org.springframework.web.servlet.NoHandlerFoundException ex,
                                                                    HttpServletRequest request) {
        logger.info("Unknown endpoint: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                errorBody(request, "Not Found", "Endpoint not found", null)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(request, "Internal Server Error", "An unexpected error occurred. Please try again later.", null));
    }
}
