package mta.eda.consumer.exception;

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
 * Mirrors the Producer's GlobalExceptionHandler for consistency.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Creates a standardized error response body.
     *
     * @param request the HTTP request
     * @param error the error type
     * @param message the error message
     * @param details additional error details
     * @return a map representing the error response
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
     * Handle validation errors (malformed request bodies, invalid field values).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        logger.warn("Validation error: {}", ex.getMessage());
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
            fieldErrors.put(err.getField(), err.getDefaultMessage())
        );
        Map<String, Object> details = new HashMap<>();
        details.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            errorBody(request, "Bad Request", "Validation error", details)
        );
    }

    /**
     * Handle malformed JSON in request body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        logger.warn("Malformed JSON received: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            errorBody(request, "Bad Request", "Invalid request body. Ensure JSON is properly formatted.", null)
        );
    }

    /**
     * Handle invalid orderId format errors.
     */
    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderId(InvalidOrderIdException ex, HttpServletRequest request) {
        logger.warn("Invalid orderId format: {}", ex.getOrderId());
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", ex.getOrderId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            errorBody(request, "Bad Request", ex.getMessage(), details)
        );
    }

    /**
     * Handle illegal argument exceptions (e.g., invalid orderId format from OrderUtils.normalizeOrderId).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        logger.warn("Illegal argument error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            errorBody(request, "Bad Request", ex.getMessage(), null)
        );
    }

    /**
     * Handle order not found errors.
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        logger.info("Order not found: {}", ex.getOrderId());
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", ex.getOrderId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            errorBody(request, "Not Found", ex.getMessage(), details)
        );
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

    /**
     * Handle all unhandled exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            errorBody(request, "Internal Server Error", "An unexpected error occurred. Please try again later.", null)
        );
    }
}
