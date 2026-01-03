package mta.eda.consumer.model.response;

/**
 * Health check status for a specific component.
 * Used to report the status of individual components of the Consumer service.
 */
public record HealthCheck(
    String status,
    String details
) {}

