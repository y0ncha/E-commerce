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
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> fieldErrors.put(err.getField(), err.getDefaultMessage()));
        Map<String, Object> details = new HashMap<>();
        details.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", "Validation error", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", "Invalid request body", null));
    }

    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderId(InvalidOrderIdException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(request, "Bad Request", ex.getMessage(), null));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(request, "Not Found", ex.getMessage(), null));
    }

    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateOrder(DuplicateOrderException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(request, "Conflict", ex.getMessage(), null));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(request, "Internal Server Error", "Internal server error", null));
    }
}
