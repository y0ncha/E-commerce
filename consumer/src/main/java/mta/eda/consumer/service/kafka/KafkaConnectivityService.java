package mta.eda.consumer.service.kafka;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KafkaConnectivityService - Manages Kafka broker connectivity with resilience patterns.
 * Features:
 * - Asynchronous background monitoring (non-blocking)
 * - Exponential backoff retry with Resilience4j
 * - Automatic Kafka listener restart when connection established
 * - Persistent retry mechanism (never gives up)
 * - Interrupt-safe listener management
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
    private final Optional<KafkaListenerEndpointRegistry> kafkaListenerEndpointRegistry;
    private final Retry kafkaRetry;
    private final ScheduledExecutorService scheduler;

    // Connection state (thread-safe)
    private final AtomicBoolean kafkaConnected = new AtomicBoolean(false);
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);

    public KafkaConnectivityService(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            Optional<KafkaListenerEndpointRegistry> kafkaListenerEndpointRegistry,
            @Autowired(required = false) RetryRegistry retryRegistry) {
        this.bootstrapServers = bootstrapServers;
        this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-connectivity-monitor");
            t.setDaemon(true);
            return t;
        });

        // Use provided registry or create a default one if missing (e.g. in tests)
        RetryRegistry registry = (retryRegistry != null) ? retryRegistry : RetryRegistry.ofDefaults();

        // Configure Resilience4j Retry with exponential backoff
        // Optimized for fast startup: 500ms initial interval, max 10s between retries
        // Retry sequence: 500ms, 1s, 2s, 4s, 8s, 10s, 10s, 10s... (capped at 10s)
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(Integer.MAX_VALUE)                          // Infinite retries
                .intervalFunction(
                        io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(
                                500,    // Initial interval: 500ms (4x faster than before)
                                2.0,    // Multiplier: 2x exponential
                                10000   // Max interval: 10 seconds (cap to avoid long waits)
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
        logger.info("Initializing Kafka connectivity monitoring service");
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
            logger.debug("Attempting Kafka connection...");

            // Execute with Resilience4j retry pattern
            try {
                kafkaRetry.executeRunnable(this::connectAndManageListeners);
            } catch (Exception e) {
                logger.debug("Kafka retry cycle completed");
            }

            // Adaptive scheduling: faster checks when disconnected, slower when connected
            // - Disconnected: 5 seconds (more aggressive monitoring)
            // - Connected: 30 seconds (maintain health, less overhead)
            long nextCheckDelay = kafkaConnected.get() ? 30000 : 5000;
            scheduleNextCheck(nextCheckDelay);

        } catch (Exception e) {
            logger.error("Unexpected error in connectivity check: {}", e.getMessage());
            // Continue monitoring with faster retry if disconnected
            long nextCheckDelay = kafkaConnected.get() ? 30000 : 5000;
            scheduleNextCheck(nextCheckDelay);
        }
    }

    /**
     * Test Kafka connection and manage listener state
     * This gets called by Resilience4j with exponential backoff
     */
    private void connectAndManageListeners() {
        boolean isConnected = testKafkaConnection();
        boolean wasConnected = kafkaConnected.get();

        if (isConnected && !wasConnected) {
            // Kafka just became available (transition from down to up)
            logger.info("✓ Kafka broker connected at {}!", bootstrapServers);
            kafkaConnected.set(true);
            startKafkaListeners();

        } else if (isConnected && !areListenersRunning()) {
            // Kafka is connected but listeners aren't running - try to start them
            logger.warn("⚠ Kafka connected but listeners not running. Attempting to start...");
            startKafkaListeners();

        } else if (!isConnected && wasConnected) {
            // Kafka just became unavailable (transition from up to down)
            logger.warn("✗ Kafka broker disconnected from {}! Will retry...", bootstrapServers);
            kafkaConnected.set(false);
            stopKafkaListeners();

        } else if (!isConnected) {
            // Still disconnected - Resilience4j will retry with exponential backoff
            throw new RuntimeException("Kafka broker unavailable at " + bootstrapServers);
        }
        // If connected and listeners running, just continue
    }

    /**
     * Test Kafka connection (3-second timeout)
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
            admin.describeCluster().nodes().get(3, java.util.concurrent.TimeUnit.SECONDS);
            return true;

        } catch (Exception e) {
            logger.warn("Kafka connectivity test failed for {}: {}", bootstrapServers, e.getMessage());
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
     * Start Kafka listeners (interrupt-safe)
     */
    private void startKafkaListeners() {
        if (kafkaListenerEndpointRegistry.isEmpty()) {
            logger.warn("⚠ KafkaListenerEndpointRegistry is not available - cannot start listeners");
            return;
        }

        kafkaListenerEndpointRegistry.ifPresent(registry -> {
            try {
                Thread listenerThread = Thread.currentThread();
                logger.info("Starting Kafka listeners (thread: {})", listenerThread.getName());

                // Check current state before starting
                boolean wasRunning = registry.isRunning();
                logger.debug("Registry running state before start: {}", wasRunning);
                logger.debug("Number of listener containers: {}", registry.getListenerContainers().size());

                registry.start();

                // Verify listeners actually started
                boolean nowRunning = registry.isRunning();
                long runningContainers = registry.getListenerContainers().stream()
                        .filter(org.springframework.kafka.listener.MessageListenerContainer::isRunning)
                        .count();

                logger.info("✓ Kafka listeners started successfully. Registry running: {}, Active containers: {}/{}",
                        nowRunning, runningContainers, registry.getListenerContainers().size());

            } catch (Exception e) {
                logger.error("✗ Failed to start Kafka listeners: {}", e.getMessage(), e);
                kafkaConnected.set(false);
            }
        });
    }

    /**
     * Stop Kafka listeners (interrupt-safe)
     */
    private void stopKafkaListeners() {
        kafkaListenerEndpointRegistry.ifPresent(registry -> {
            try {
                logger.info("Stopping Kafka listeners...");
                registry.stop();
                logger.info("✓ Kafka listeners stopped");

            } catch (Exception e) {
                logger.error("✗ Failed to stop Kafka listeners: {}", e.getMessage());
            }
        });
    }

    /**
     * Get the current Kafka connection state
     * Used by HealthService for health checks (instant response)
     */
    public boolean isKafkaConnected() {
        return kafkaConnected.get();
    }

    /**
     * Check if Kafka listeners are actually running and consuming.
     * More accurate than just connection status.
     */
    public boolean areListenersRunning() {
        return kafkaListenerEndpointRegistry
                .map(registry -> registry.isRunning() && registry.getListenerContainers().stream()
                        .anyMatch(org.springframework.kafka.listener.MessageListenerContainer::isRunning))
                .orElse(false);
    }

    /**
     * Get detailed status for health checks
     */
    public String getDetailedStatus() {
        boolean connected = kafkaConnected.get();
        boolean listenersRunning = areListenersRunning();

        if (!connected) {
            return "DOWN - Cannot connect to Kafka broker at " + bootstrapServers;
        }

        if (!listenersRunning) {
            return "DEGRADED - Connected to broker but listeners not running";
        }

        return "UP - Connected and consuming from topics";
    }

}
