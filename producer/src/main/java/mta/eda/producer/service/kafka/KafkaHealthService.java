package mta.eda.producer.service.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Value("${producer.health.warn.throttle.ms:10000}")
    private long warnThrottleMs;

    private final Object lock = new Object();
    private volatile long lastCheckedAtMs = 0;
    private volatile KafkaStatus lastStatus = new KafkaStatus(false, "Not checked yet");
    private volatile long lastWarnAtMs = 0;

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
                throttleWarn(topicName, status.reason());
            } else {
                logger.debug("Kafka readiness UP - topic={}", topicName);
            }

            return status;
        }
    }

    /**
     * Liveness check: always UP if the service is running.
     * (We keep it here so the controller has a single dependency for health endpoints.)
     */
    public Map<String, Object> liveness() {
        return Map.of(
                "status", "UP",
                "message", "Producer service is running"
        );
    }

    private KafkaStatus checkOnce() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildAdminProperties());
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

    private void throttleWarn(Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAtMs >= warnThrottleMs) {
            lastWarnAtMs = now;
            logger.warn("Kafka readiness DOWN - topic={} - reason={}", args);
        } else {
            logger.debug("Kafka readiness DOWN - topic={} - reason={}", args);
        }
    }
}