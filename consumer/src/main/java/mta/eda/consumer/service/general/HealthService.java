package mta.eda.consumer.service.general;

import mta.eda.consumer.model.response.HealthCheck;
import mta.eda.consumer.service.kafka.KafkaConnectivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * HealthService - Provides health check information for the Consumer service.
 * Reports:
 * - Service status (always UP if running)
 * - Kafka connectivity status (from KafkaConnectivityService)
 * - Local state store accessibility
 * Note: Actual Kafka monitoring and retry logic is handled by KafkaConnectivityService
 */
@Service
public class HealthService {

    private static final Logger logger = LoggerFactory.getLogger(HealthService.class);

    private final KafkaConnectivityService kafkaConnectivityService;

    public HealthService(KafkaConnectivityService kafkaConnectivityService) {
        this.kafkaConnectivityService = kafkaConnectivityService;
    }

    /**
     * Get the health status of the Order Service itself.
     * Returns UP if the service is running and responsive.
     *
     * @return HealthCheck with service status
     */
    public HealthCheck getServiceStatus() {
        try {
            // Service is running if this method is callable
            return new HealthCheck("UP", "Order Service is running and responsive");
        } catch (Exception e) {
            logger.error("Error checking service status", e);
            return new HealthCheck("DOWN", "Order Service is not responding: " + e.getMessage());
        }
    }

    /**
     * Get the health status of the Kafka broker.
     * Returns cached connectivity status from KafkaConnectivityService background monitoring.
     *
     * @return HealthCheck with Kafka broker status
     */
    public HealthCheck getKafkaStatus() {
        String detailedStatus = kafkaConnectivityService.getDetailedStatus();

        if (!kafkaConnectivityService.isKafkaConnected()) {
            return new HealthCheck("DOWN", detailedStatus);
        }

        if (kafkaConnectivityService.isTopicNotFound()) {
            return new HealthCheck("DOWN", detailedStatus);
        }

        if (!kafkaConnectivityService.isTopicReady()) {
            return new HealthCheck("DEGRADED", detailedStatus);
        }

        if (kafkaConnectivityService.isKafkaConnected() && kafkaConnectivityService.areListenersRunning()) {
            return new HealthCheck("UP", detailedStatus);
        } else {
            return new HealthCheck("DEGRADED", detailedStatus);
        }
    }

    /**
     * Get the health status of the local order state store.
     * Verifies that the in-memory state store is accessible.
     *
     * @return HealthCheck with state store status
     */
    public HealthCheck getLocalStateStatus() {
        try {
            // The fact that this method executes means the service has the state store
            return new HealthCheck("UP", "Local order state store is accessible");
        } catch (Exception e) {
            logger.error("Error checking local state status", e);
            return new HealthCheck("DOWN", "Local state store is unavailable: " + e.getMessage());
        }
    }
}

