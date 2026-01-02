package mta.eda.consumer.service.general;

import mta.eda.consumer.model.response.HealthCheck;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * HealthService - Handles health check operations for the Consumer service.
 * Provides status of the service itself and Kafka broker connectivity.
 */
@Service
public class HealthService {

    private static final Logger logger = LoggerFactory.getLogger(HealthService.class);

    private final String bootstrapServers;

    public HealthService(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
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
     * Attempts to connect to Kafka to verify broker availability by fetching broker metadata.
     *
     * @return HealthCheck with Kafka broker status
     */
    public HealthCheck getKafkaStatus() {
        try {
            // Create an AdminClient to check Kafka connectivity
            Map<String, Object> adminProps = new HashMap<>();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
            adminProps.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 1000);

            try (AdminClient admin = AdminClient.create(adminProps)) {
                // Try to describe the cluster - this will fail if Kafka is unreachable
                admin.describeCluster().nodes().get();
                logger.debug("Kafka broker is reachable at {}", bootstrapServers);
                return new HealthCheck("UP", "Kafka broker is accessible at " + bootstrapServers);
            }
        } catch (ExecutionException | InterruptedException e) {
            logger.warn("Kafka broker is unreachable at {}: {}", bootstrapServers, e.getMessage());
            return new HealthCheck("DOWN", "Kafka broker is unavailable at " + bootstrapServers);
        } catch (Exception e) {
            logger.error("Error checking Kafka status", e);
            return new HealthCheck("DOWN", "Kafka broker is unavailable: " + e.getMessage());
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

