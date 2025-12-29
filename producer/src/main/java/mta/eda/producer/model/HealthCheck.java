package mta.eda.producer.model;

/**
 * HealthCheck - Individual health check result.
 * Used within HealthResponse to report status of a specific component.
 */
public record HealthCheck(
    String status,    // "UP" or "DOWN"
    String details    // Technical reason (e.g., "JVM running", "topic exists", "timeout")
) {}

