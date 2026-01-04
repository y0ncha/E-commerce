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
     * Get Kafka status with fresh ping check (non-blocking, single attempt).
     * Pings Kafka to verify current status and updates cache if changed.
     * Uses cached status if ping confirms no change.
     */
    public HealthCheck getKafkaStatus() {
        // Ping Kafka to get fresh status (updates cache if changed)
        kafkaConnectivityService.pingKafka();

        // Return current status (potentially just updated by ping)
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
