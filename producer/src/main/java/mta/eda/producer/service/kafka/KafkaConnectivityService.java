package mta.eda.producer.service.kafka;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KafkaConnectivityService - Manages Kafka broker connectivity with resilience patterns.
 * Features:
 * - Asynchronous background monitoring (non-blocking)
 * - Exponential backoff retry with Resilience4j
 * - Pre-cached status for fast HTTP health endpoints
 * - Persistent retry mechanism (never gives up)
 */
@Service
public class KafkaConnectivityService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConnectivityService.class);

    /**
     * -- GETTER --
     *  Get Kafka bootstrap servers address
     */
    @Getter
    private final String bootstrapServers;
    @Getter
    private final String topicName;
    private final Retry kafkaRetry;
    private final ScheduledExecutorService scheduler;

    // Connection state (thread-safe)
    private final AtomicBoolean kafkaConnected = new AtomicBoolean(false);
    private final AtomicBoolean topicReady = new AtomicBoolean(false);
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    private final AtomicBoolean topicNotFound = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>("Initializing...");

    public KafkaConnectivityService(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${kafka.topic.name:orders}") String topicName,
            @Autowired(required = false) RetryRegistry retryRegistry) {
        this.bootstrapServers = bootstrapServers;
        this.topicName = topicName;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-connectivity-monitor");
            t.setDaemon(true);
            return t;
        });

        // Use provided registry or create a default one if missing (e.g. in tests)
        RetryRegistry registry = (retryRegistry != null) ? retryRegistry : RetryRegistry.ofDefaults();

        // Configure Resilience4j Retry with exponential backoff
        // Optimized for faster initial recovery: start at 100ms and cap at 5s
        // Retry sequence: 100ms, 200ms, 400ms, 800ms, 1.6s, 3.2s, 5s, 5s... (capped at 5s)
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(Integer.MAX_VALUE)                          // Infinite retries
                .intervalFunction(
                        io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(
                                100,    // Initial interval: 100ms for aggressive first retries
                                2.0,    // Multiplier: 2x exponential
                                5000    // Max interval: 5 seconds to keep reconnects responsive
                        )
                )
                .retryOnException(e -> true)                             // Retry on any exception
                .failAfterMaxAttempts(false)                             // Never give up
                .build();

        this.kafkaRetry = registry.retry("kafka-connectivity", retryConfig);

        // Log backoff event details
        kafkaRetry.getEventPublisher()
                .onRetry(event -> logger.debug(
                        "Kafka retry #{}: {}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable() != null ? event.getLastThrowable().getMessage() : "Unknown error"
                ));
    }

    /**
     * Start monitoring Kafka connectivity asynchronously on application startup
     */
    @PostConstruct
    public void startMonitoring() {
        logger.info("Initializing Kafka connectivity monitoring service for topic '{}'", topicName);
        monitoringActive.set(true);
        scheduleNextCheck(0);  // Start immediately
    }

    /**
     * Stop monitoring on application shutdown
     */
    @PreDestroy
    public void stopMonitoring() {
        logger.info("Stopping Kafka connectivity monitoring service");
        monitoringActive.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Schedule the next Kafka connectivity check (non-blocking)
     */
    private void scheduleNextCheck(long delayMs) {
        if (monitoringActive.get()) {
            scheduler.schedule(this::checkKafkaConnectivity, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Check Kafka connectivity and schedule next check
     * Non-blocking, runs in scheduled executor
     */
    private void checkKafkaConnectivity() {
        try {
            logger.debug("Attempting Kafka connection check...");

            // Execute with Resilience4j retry pattern
            try {
                kafkaRetry.executeRunnable(this::connectAndCheckTopic);
            } catch (Exception e) {
                logger.debug("Kafka retry cycle completed");
            }

            // Adaptive scheduling: probe faster until ready
            boolean connected = kafkaConnected.get();
            boolean ready = topicReady.get();

            long nextCheckDelay;
            if (!connected) {
                nextCheckDelay = 1000;       // disconnected: retry quickly
            } else if (!ready) {
                nextCheckDelay = 1000;       // connected but topic not ready: restart ASAP
            } else {
                nextCheckDelay = 30000;      // healthy: back off
            }

            scheduleNextCheck(nextCheckDelay);

        } catch (Exception e) {
            logger.error("Unexpected error in connectivity check: {}", e.getMessage());
            // Continue monitoring with faster retry if not ready
            boolean connected = kafkaConnected.get();
            boolean ready = topicReady.get();
            long nextCheckDelay = (!connected || !ready) ? 1000 : 30000;
            scheduleNextCheck(nextCheckDelay);
        }
    }

    /**
     * Test Kafka connection and verify topic exists
     * This gets called by Resilience4j with exponential backoff
     */
    private void connectAndCheckTopic() {
        boolean isConnected = testKafkaConnection();
        boolean wasConnected = kafkaConnected.get();
        boolean topicExists = isConnected && verifyTopicExists();
        boolean wasReady = topicReady.get();

        if (isConnected && !wasConnected) {
            // Kafka just became available
            logger.info("✓ Kafka broker connected at {}!", bootstrapServers);
            kafkaConnected.set(true);
        } else if (!isConnected && wasConnected) {
            // Kafka just became unavailable
            logger.warn("✗ Kafka broker disconnected from {}! Will retry...", bootstrapServers);
            kafkaConnected.set(false);
            topicReady.set(false);
            return;
        } else if (!isConnected) {
            // Still disconnected - Resilience4j will retry
            throw new RuntimeException("Kafka broker unavailable at " + bootstrapServers);
        }

        // Now check topic if connected
        if (topicExists && !wasReady) {
            logger.info("✓ Kafka topic '{}' ready for use!", topicName);
            topicReady.set(true);
        } else if (!topicExists && wasReady) {
            logger.warn("✗ Kafka topic '{}' became unavailable! Will retry...", topicName);
            topicReady.set(false);
        } else if (!topicExists) {
            throw new RuntimeException("Kafka topic '" + topicName + "' not available");
        }
    }

    /**
     * Test Kafka broker connection (3-second timeout)
     */
    private boolean testKafkaConnection() {
        AdminClient admin = null;
        try {
            Map<String, Object> adminProps = new HashMap<>();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
            adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
            adminProps.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 1000);

            admin = AdminClient.create(adminProps);
            var brokers = admin.describeCluster()
                    .nodes()
                    .get(3, TimeUnit.SECONDS);
            return !brokers.isEmpty();

        } catch (Exception e) {
            logger.debug("Kafka connectivity test failed for {}: {}", bootstrapServers, e.getMessage());
            return false;

        } finally {
            if (admin != null) {
                try {
                    admin.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Verify that the topic exists and has ready partitions
     */
    private boolean verifyTopicExists() {
        AdminClient admin = null;
        try {
            Map<String, Object> adminProps = new HashMap<>();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
            adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);

            admin = AdminClient.create(adminProps);
            var topicDesc = admin.describeTopics(List.of(topicName))
                    .topicNameValues()
                    .get(topicName)
                    .get(3, TimeUnit.SECONDS);

            // Verify all partitions have a leader
            boolean ready = topicDesc.partitions().stream()
                    .allMatch(p -> p.leader() != null);

            if (ready) {
                topicNotFound.set(false);
                lastError.set(null);
            }
            return ready;

        } catch (java.util.concurrent.TimeoutException te) {
            logger.debug("Topic verification timed out for '{}': {}", topicName, te.getMessage());
            lastError.set("Topic verification timeout");
            return false;
        } catch (Exception e) {
            // Check if this is a topic-not-found error
            if (isTopicNotFoundException(e)) {
                topicNotFound.set(true);
                lastError.set("Topic '" + topicName + "' does not exist");
                logger.warn("✗ Topic '{}' not found! Auto-creation may be disabled.", topicName);
            } else {
                topicNotFound.set(false);
                lastError.set("Topic verification failed: " + e.getMessage());
                logger.debug("Topic verification failed for '{}': {}", topicName, e.getMessage());
            }
            return false;

        } finally {
            if (admin != null) {
                try {
                    admin.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Check if the exception chain contains UnknownTopicOrPartitionException
     * This indicates the topic doesn't exist and auto-creation is disabled
     */
    public boolean isTopicNotFoundException(Throwable e) {
        // First, check if this is a connection/timeout issue - these are NOT topic issues
        Throwable cause = e;
        while (cause != null) {
            // Connection/timeout exceptions indicate Kafka is down, not a topic issue
            if (cause instanceof java.util.concurrent.TimeoutException ||
                cause instanceof java.io.IOException ||
                cause instanceof org.apache.kafka.common.errors.TimeoutException) {
                String message = cause.getMessage();
                // If message indicates connection/timeout, this is NOT a topic issue
                if (message != null && (
                    message.contains("timed out") ||
                    message.contains("Connection refused") ||
                    message.contains("Failed to update metadata") ||
                    message.contains("broker") ||
                    message.contains("not available"))) {
                    return false;
                }
            }
            cause = cause.getCause();
        }

        // Now check for actual topic-not-found exceptions
        cause = e;
        while (cause != null) {
            if (cause instanceof UnknownTopicOrPartitionException) {
                return true;
            }
            // Only check message for very specific topic-related errors
            // Avoid broad matches that could trigger on connection issues
            String message = cause.getMessage();
            if (message != null && message.contains("UnknownTopicOrPartition")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Get the current Kafka connection state (instant response - no blocking)
     */
    @SuppressWarnings("unused")
    public boolean isKafkaConnected() {
        return kafkaConnected.get();
    }

    /**
     * Check if Kafka topic is ready (instant response - no blocking)
     */
    @SuppressWarnings("unused")
    public boolean isTopicReady() {
        return topicReady.get();
    }

    /**
     * Check if topic was not found (instant response - no blocking)
     */
    @SuppressWarnings("unused")
    public boolean isTopicNotFound() {
        return topicNotFound.get();
    }

    /**
     * Get detailed status for health checks (instant response - no blocking)
     */
    @SuppressWarnings("unused")
    public boolean isHealthy() {
        return kafkaConnected.get() && topicReady.get() && !topicNotFound.get();
    }

    /**
     * Get the last error reason (instant response - no blocking)
     */
    @SuppressWarnings("unused")
    public String getDetailedStatus() {
        if (!kafkaConnected.get()) {
            return "Cannot connect to Kafka broker at " + bootstrapServers;
        }
        if (topicNotFound.get()) {
            return "Topic '" + topicName + "' does not exist";
        }
        if (!topicReady.get()) {
            return "Connected to broker but topic '" + topicName + "' not ready";
        }
        return "Kafka broker and topic '" + topicName + "' are ready";
    }

    /**
     * Get the last error message
     */
    @SuppressWarnings("unused")
    public String getLastError() {
        return lastError.get();
    }

    /**
     * Ping Kafka to get fresh status without retries.
     * Called by health endpoints to ensure status is up-to-date.
     * Updates cached state if status has changed.
     *
     * @return true if Kafka is healthy (connected and topic ready)
     */
    public boolean pingKafka() {
        try {
            // Quick connectivity check (no retries, just one attempt)
            boolean isConnected = testKafkaConnection();
            boolean wasConnected = kafkaConnected.get();

            if (isConnected != wasConnected) {
                // Status changed - update cache
                kafkaConnected.set(isConnected);
                if (isConnected) {
                    logger.info("✓ Kafka ping: Broker reconnected at {}!", bootstrapServers);
                } else {
                    logger.warn("✗ Kafka ping: Broker disconnected from {}!", bootstrapServers);
                    topicReady.set(false);
                    return false;
                }
            }

            if (!isConnected) {
                return false;
            }

            // Check topic if connected
            boolean topicExists = verifyTopicExists();
            boolean wasReady = topicReady.get();

            if (topicExists != wasReady) {
                // Topic status changed - update cache
                topicReady.set(topicExists);
                if (topicExists) {
                    logger.info("✓ Kafka ping: Topic '{}' is now ready!", topicName);
                } else {
                    logger.warn("✗ Kafka ping: Topic '{}' became unavailable!", topicName);
                }
            }

            return isConnected && topicExists;

        } catch (Exception e) {
            logger.debug("Kafka ping failed: {}", e.getMessage());
            return false;
        }
    }
}

