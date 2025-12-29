package mta.eda.producer.service.kafka;

import mta.eda.producer.model.HealthCheck;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * KafkaHealthService
 * Performs readiness checks against Kafka using AdminClient.
 * Designed to be safe for HTTP endpoints (short timeouts + caching).
 */
@Service
public class KafkaHealthService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaHealthService.class);

    private final KafkaProperties kafkaProperties;

    @Value("${kafka.topic.name}")
    private String topicName;

    @Value("${producer.health.timeout.ms:1000}")
    private long healthTimeoutMs;

    @Value("${producer.health.cache.ttl.ms:2000}")
    private long cacheTtlMs;

    private final Object lock = new Object();
    private volatile long lastCheckedAtMs = 0;
    private volatile KafkaStatus lastStatus = new KafkaStatus(false, "Not checked yet");

    public KafkaHealthService(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    public record KafkaStatus(boolean healthy, String reason) {}

    /**
     * Readiness check: Kafka reachable AND topic exists.
     * Cached to avoid spamming Kafka and logs.
     */
    public KafkaStatus readiness() {
        long now = System.currentTimeMillis();
        if (now - lastCheckedAtMs <= cacheTtlMs) {
            return lastStatus;
        }

        synchronized (lock) {
            now = System.currentTimeMillis();
            if (now - lastCheckedAtMs <= cacheTtlMs) {
                return lastStatus;
            }

            KafkaStatus status = checkOnce();
            lastStatus = status;
            lastCheckedAtMs = now;

            if (!status.healthy()) {
                logger.warn("Kafka readiness DOWN - topic={} - reason={}", topicName, status.reason());
            } else {
                logger.debug("Kafka readiness UP - topic={}", topicName);
            }

            return status;
        }
    }

    /**
     * Get detailed Kafka status for health response.
     * Returns status and details suitable for HealthResponse.
     */
    public HealthCheck getKafkaStatus() {
        KafkaStatus status = readiness();
        String details = status.healthy()
                ? "reachable; topic '" + topicName + "' exists"
                : status.reason();
        return new HealthCheck(
                status.healthy() ? "UP" : "DOWN",
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
                "web server started"
        );
    }

    private KafkaStatus checkOnce() {
        var props = kafkaProperties.buildAdminProperties();
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) healthTimeoutMs);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) healthTimeoutMs);

        try (AdminClient admin = AdminClient.create(props)) {
            admin.describeCluster().nodes().get(healthTimeoutMs, TimeUnit.MILLISECONDS);
            admin.describeTopics(List.of(topicName))
                    .allTopicNames()
                    .get(healthTimeoutMs, TimeUnit.MILLISECONDS);

            return new KafkaStatus(true, "OK");
        } catch (Exception e) {
            return new KafkaStatus(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}

