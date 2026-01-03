package mta.eda.producer.service.general;

import mta.eda.producer.model.response.HealthCheck;
import mta.eda.producer.service.kafka.KafkaConnectivityService;
import org.springframework.stereotype.Service;

/**
 * HealthService
 * Provides health checks using pre-cached status from KafkaConnectivityService.
 * No blocking I/O - status is updated asynchronously in background.
 */
@Service
public class HealthService {

    private final KafkaConnectivityService kafkaConnectivityService;

    public HealthService(KafkaConnectivityService kafkaConnectivityService) {
        this.kafkaConnectivityService = kafkaConnectivityService;
    }

    /**
     * Get Kafka status from background monitoring service (instant response - no blocking).
     * Status is updated asynchronously every 1s until healthy, then every 30s.
     */
    public HealthCheck getKafkaStatus() {
        boolean healthy = kafkaConnectivityService.isHealthy();
        String details = kafkaConnectivityService.getDetailedStatus();
        return new HealthCheck(
                healthy ? "UP" : "DOWN",
                details
        );
    }

    /**
     * Get service status for health response.
     * Always UP since the service is running.
     */
    public HealthCheck getServiceStatus() {
        return new HealthCheck(
                "UP",
                "Cart Service is running and responsive"
        );
    }
}
