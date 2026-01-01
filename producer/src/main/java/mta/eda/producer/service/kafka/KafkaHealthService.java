package mta.eda.producer.service.kafka;

import mta.eda.producer.model.HealthCheck;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * KafkaHealthService
 * Performs readiness checks against Kafka using AdminClient.
 * Designed to be safe for HTTP endpoints (short timeouts + caching).
 */
@Service
public class KafkaHealthService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaHealthService.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.topic.name}")
    private String topicName;

    @Value("${producer.health.timeout.ms:3000}")
    private long healthTimeoutMs;

    @Value("${producer.health.cache.ttl.ms:2000}")
    private long cacheTtlMs;

    private final Object lock = new Object();
    private volatile long lastCheckedAtMs = 0;
    private volatile KafkaStatus lastStatus = new KafkaStatus(false, "Not checked yet");

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
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) healthTimeoutMs);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) healthTimeoutMs);

        try (AdminClient admin = AdminClient.create(props)) {

            // 1) Verify broker is reachable
            try {
                var brokers = admin.describeCluster()
                        .nodes()
                        .get(healthTimeoutMs, TimeUnit.MILLISECONDS);

                if (brokers.isEmpty()) {
                    return new KafkaStatus(false, "BROKER_DOWN: Cluster returned no nodes. Check bootstrap-servers: " + bootstrapServers);
                }
            } catch (java.util.concurrent.TimeoutException te) {
                return new KafkaStatus(false, String.format("CLUSTER_TIMEOUT: Failed to connect to Kafka brokers within %dms. Check if brokers are running at %s",
                        healthTimeoutMs, bootstrapServers));
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                return new KafkaStatus(false, "CLUSTER_ERROR: " + (cause != null ? cause.getMessage() : ee.getMessage()));
            }

            // 2) Verify topic exists
            try {
                DescribeTopicsResult dtr = admin.describeTopics(List.of(topicName));
                TopicDescription topicDesc = dtr.topicNameValues()
                        .get(topicName)
                        .get(healthTimeoutMs, TimeUnit.MILLISECONDS);

                // 3) Verify topic has at least one partition with a leader
                for (TopicPartitionInfo p : topicDesc.partitions()) {
                    if (p.leader() == null) {
                        return new KafkaStatus(false, String.format("NO_LEADER: Topic '%s' partition %d has no leader. Check broker logs.", topicName, p.partition()));
                    }
                }

                return new KafkaStatus(true, "OK");

            } catch (java.util.concurrent.TimeoutException te) {
                return new KafkaStatus(false, String.format("TOPIC_TIMEOUT: Timed out (%dms) fetching metadata for topic '%s'.", healthTimeoutMs, topicName));
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof UnknownTopicOrPartitionException) {
                    return new KafkaStatus(false, "TOPIC_NOT_FOUND: Topic '" + topicName + "' does not exist. Ensure it is created.");
                }
                return new KafkaStatus(false, "TOPIC_ERROR: " + (cause != null ? cause.getMessage() : ee.getMessage()));
            }

        } catch (Exception e) {
            return new KafkaStatus(false, "UNEXPECTED_ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
