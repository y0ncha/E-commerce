package mta.eda.consumer.model.response;

import java.util.Map;

/**
 * Health response for liveness and readiness probes.
 * Provides comprehensive health information about the Consumer service.
 * Includes serviceName, probe type, status, timestamp, and per-component checks.
 */
public record HealthResponse(
    String serviceName,
    String type,           // "liveness" or "readiness"
    String status,         // "UP" or "DOWN"
    String timestamp,      // ISO-8601 format
    Map<String, HealthCheck> checks
) {}

