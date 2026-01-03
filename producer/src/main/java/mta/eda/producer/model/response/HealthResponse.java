package mta.eda.producer.model.response;

import java.util.Map;

/**
 * HealthResponse - Structured response for health check endpoints.
 * Provides consistent schema for both liveness and readiness probes.
 */
public record HealthResponse(
    String serviceName,
    String type,           // "liveness" or "readiness"
    String status,         // "UP" or "DOWN"
    String timestamp,      // ISO-8601 format
    Map<String, HealthCheck> checks
) {}


