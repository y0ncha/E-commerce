# CONFIG.md: Consumer Configuration

This document explains all configuration options available for the **Order Service (Consumer)** and the rationale behind each choice.

**Course Context**: These configurations implement **Session 6 - Consumer Patterns** and **Session 7 - Offset Management** from the MTA Event-Driven Architecture course, focusing on manual offset commits and At-Least-Once delivery semantics.

---

## Overview

The consumer is configured through multiple layers:
1. **Default Values** in `application.properties`
2. **Environment Variables** (Docker Compose)
3. **Command-Line Overrides** (runtime)

Configuration values follow the precedence: **Command-line → Environment Variables → application.properties defaults**

---

## Spring Boot Configuration (application.properties)

### Server Configuration

#### `server.port=8082`
- **Purpose**: HTTP port for the consumer service
- **Default**: `8082`
- **Why This Value**: Avoids conflict with producer (8081) and common ports
- **Override**: `SERVER_PORT=8083 docker-compose up -d`

#### `spring.application.name=order-service`
- **Purpose**: Internal application identifier
- **Default**: `order-service`
- **Why This Value**: Clearly identifies the service in logs and monitoring
- **Used By**: Spring Boot actuators, metrics, and health endpoints

---

## Kafka Configuration

### Bootstrap Servers

#### `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- **Purpose**: Address(es) of Kafka brokers
- **Default**: `localhost:9092` (for local development)
- **Docker Override**: `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` (set in docker-compose.yml)
- **Why This Value**: 
  - Docker: Uses hostname resolution (internal container name `kafka`)
  - Port 29092 is the internal protocol port (not exposed externally)
  - Configured by producer's docker-compose
  - Local Dev: Defaults to `localhost:9092` for development without Docker
- **Override Examples**:
  - Docker: `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` (set in docker-compose.yml)
  - Local Dev: Use default `localhost:9092`
  - Remote: `KAFKA_BOOTSTRAP_SERVERS=kafka-broker-1.example.com:9092,kafka-broker-2.example.com:9092`

### Consumer Group Configuration

#### `spring.kafka.consumer.group-id=order-service-group`
- **Purpose**: Kafka consumer group identifier
- **Default**: `order-service-group`
- **Why This Value**: 
  - Unique identifier for this consumer's group
  - Multiple instances with same group-id share partitions
  - Named clearly to indicate it processes orders
- **Behavior**: All messages are replayed if this group is new or offset is reset
- **Multiple Instances**: If you run 2+ consumer instances with same group-id, Kafka automatically distributes partitions

#### `spring.kafka.consumer.group-id=${SPRING_KAFKA_CONSUMER_GROUP_ID}`
- **Alternative Override**: Via environment variable
- **Example**: `SPRING_KAFKA_CONSUMER_GROUP_ID=order-service-group-v2`

### Kafka Connection Timeouts (Optimized for Fast Startup)

The consumer is configured with aggressive timeout settings for faster failure detection and reconnection:

#### `spring.kafka.properties.connections.max.idle.ms=5000`
- **Purpose**: Maximum idle time before closing idle connections
- **Default in Kafka**: 540000ms (9 minutes)
- **Our Value**: 5000ms (5 seconds)
- **Why Reduced**: Faster detection of dead connections, quicker cleanup of idle connections
- **Impact**: Connections that are idle for >5 seconds are closed and recreated when needed

#### `spring.kafka.consumer.properties.session.timeout.ms=10000`
- **Purpose**: Timeout for consumer group session (heartbeat detection)
- **Default in Kafka**: 45000ms (45 seconds)
- **Our Value**: 10000ms (10 seconds)
- **Why Reduced**: Faster broker failure detection, quicker rebalancing
- **Impact**: Consumer must send heartbeat within 10s or be removed from group

#### `spring.kafka.consumer.properties.heartbeat.interval.ms=2000`
- **Purpose**: Frequency of heartbeat messages to broker
- **Default in Kafka**: 3000ms (3 seconds)
- **Our Value**: 2000ms (2 seconds)
- **Why Reduced**: More frequent liveness detection
- **Relationship**: Must be less than `session.timeout.ms / 3` (10000 / 3 = 3333ms)

#### `spring.kafka.consumer.properties.metadata.max.age.ms=5000`
- **Purpose**: How often to refresh cluster metadata
- **Default in Kafka**: 300000ms (5 minutes)
- **Our Value**: 5000ms (5 seconds)
- **Why Reduced**: Faster detection of topic/partition changes, quicker startup
- **Impact**: Consumer refreshes broker/topic metadata every 5 seconds

#### `spring.kafka.consumer.properties.request.timeout.ms=10000`
- **Purpose**: Maximum wait time for request responses
- **Default in Kafka**: 30000ms (30 seconds)
- **Our Value**: 10000ms (10 seconds)
- **Why Reduced**: Faster retry cycles, quicker failure detection
- **Impact**: Requests that take >10s will timeout and retry

**Timeout Configuration Summary:**
```
Optimized Values (Fast Recovery):
- connections.max.idle.ms:    5s  (vs default 540s)
- session.timeout.ms:         10s (vs default 45s)
- heartbeat.interval.ms:       2s (vs default 3s)
- metadata.max.age.ms:         5s (vs default 300s)
- request.timeout.ms:         10s (vs default 30s)

Benefits:
✅ Faster startup (metadata refresh every 5s vs 5min)
✅ Quicker failure detection (10s session vs 45s)
✅ Faster recovery from network issues
✅ More responsive to broker topology changes

Trade-offs:
⚠ Slightly more network traffic (frequent heartbeats/metadata)
⚠ Less tolerant of brief network hiccups
```

### Kafka Admin Configuration

#### `spring.kafka.admin.fail-fast=false`
- **Purpose**: Controls startup behavior when Kafka is unavailable
- **Default**: `true` (application fails to start if Kafka is down)
- **Our Value**: `false`
- **Why `false`**: 
  - Allows consumer to start even when Kafka is unavailable
  - Essential for standalone testing and development
  - Works with KafkaConnectivityService for background monitoring
  - Prevents crash loops during Kafka maintenance
- **Behavior**: Application starts successfully; KafkaConnectivityService monitors and auto-reconnects

### Offset Management (Critical for At-Least-Once)

#### `spring.kafka.consumer.auto-offset-reset=earliest`
- **Purpose**: How to handle missing/invalid offsets
- **Default**: `earliest`
- **Possible Values**:
  - `earliest`: Start from the beginning of the topic (reprocess all messages)
  - `latest`: Start from the end (skip historical messages)
  - `none`: Throw error if offset is missing
- **Why `earliest`**: 
  - Ensures no events are lost on first startup
  - Aligns with "At-Least-Once" delivery semantics
  - Safe default (can reprocess, not skip)
  - **Course Requirement**: Guarantees all order events are eventually processed
- **When to Change**:
  - Change to `latest` if you only want new messages (but will lose historical state)
  - Use `none` if you explicitly manage offsets externally

#### `spring.kafka.consumer.enable-auto-commit=false`
- **Purpose**: Automatic vs. manual offset commits
- **Default**: `false` (MANDATORY for this course)
- **Why `false`**:
  - **Critical for At-Least-Once**: Manual control over when to commit
  - Consumer only commits offset AFTER state is successfully persisted
  - If consumer crashes before commit, message is redelivered on restart
  - Prevents losing events due to early commits
  - **Course Requirement (Session 7)**: Demonstrates understanding of offset management
- **Do NOT Change**: This is a course requirement for reliability

**At-Least-Once Delivery Flow:**

```mermaid
sequenceDiagram
    participant Kafka as Kafka Broker
    participant Consumer as KafkaConsumerService
    participant State as processedOrderStore
    participant Commit as Offset Commit

    Kafka->>Consumer: Deliver message (offset 42)
    Consumer->>Consumer: Deserialize & validate
    Consumer->>State: Update order state
    
    alt Success Path
        State-->>Consumer: State updated successfully
        Consumer->>Commit: acknowledge() → commit offset 42
        Commit-->>Kafka: Offset 42 committed
        Note over Kafka: Next poll starts from offset 43
    else Failure Path (Crash before commit)
        State-->>Consumer: State update failed / Crash
        Note over Commit: Offset 42 NOT committed
        Note over Kafka: Next poll starts from offset 42 (redelivery)
        Note over Consumer: Consumer restarts
        Kafka->>Consumer: Redeliver message (offset 42)
        Note over Consumer: Idempotency check detects duplicate<br/>or reprocesses safely
    end
```

**Why This Matters:**
- **Auto-commit enabled** (bad): Offsets committed before processing → message loss on crash
- **Auto-commit disabled** (good): Offsets committed after processing → guaranteed delivery

---

## Manual Acknowledgment (At-Least-Once Delivery)

### Container Factory Configuration (KafkaConsumerConfig.java)

#### `AckMode.MANUAL_IMMEDIATE`
- **Purpose**: When to commit offsets
- **Options**:
  - `MANUAL`: Commit only when `acknowledgment.acknowledge()` is called
  - `MANUAL_IMMEDIATE`: Commit after `acknowledge()` call returns (immediate)
  - `AUTO`: Automatic commit (NOT ALLOWED for this course)
  - `BATCH`: Commit when batch is complete
  - `RECORD`: Commit after each record
- **Why `MANUAL_IMMEDIATE`**:
  - Precise control: commit happens immediately after state update
  - Synchronous: easier to reason about failure scenarios
  - Safer than `MANUAL` which could lose commits on crash
  - **Course Requirement (Session 7)**: Demonstrates "commit after processing" pattern
- **Flow**:
  ```
  Message received → Deserialize → Process → State updated → acknowledge() → Offset committed
  ```

**Configuration in Code:**

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        ConsumerFactory<String, String> consumerFactory) {
    
    ConcurrentKafkaListenerContainerFactory<String, String> factory = 
        new ConcurrentKafkaListenerContainerFactory<>();
    
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    
    // UPDATED: Enable auto-startup for immediate consumption
    // Spring manages the lifecycle, while KafkaConnectivityService monitors health
    factory.setAutoStartup(true);
    
    return factory;
}
```

**Comparison of Acknowledgment Modes:**

| Mode | When Committed | Use Case | Risk |
|------|---------------|----------|------|
| **AUTO** | After poll interval | High throughput, can tolerate message loss | Message loss on crash |
| **RECORD** | After each message | Low latency, minimal redelivery | Higher commit overhead |
| **BATCH** | After batch complete | Balance throughput & safety | Entire batch redelivered on crash |
| **MANUAL** | Explicit acknowledge() call | Full control, batching possible | Must manage commits carefully |
| **MANUAL_IMMEDIATE** | After acknowledge() returns | Full control + immediate commit | Slight overhead per message |

**Our Choice: MANUAL_IMMEDIATE**
- ✅ Guarantees offset committed after state persisted
- ✅ Synchronous behavior (predictable)
- ✅ Idempotency handles redeliveries gracefully
- ✅ Aligns with course requirement for manual offset management

---

## Topic Configuration and Partition Strategy Interaction

### Consumer-Side Topic Configuration

#### `kafka.consumer.topic=${KAFKA_TOPIC:orders}`
- **Purpose**: Which Kafka topic the consumer listens to
- **Default**: `orders`
- **Why `orders`**: Standard topic name matching producer
- **Must Match Producer**: Producer publishes to `order-events`, consumer reads from same topic
- **Override Priority**:
  1. Command-line: `KAFKA_TOPIC=my-topic docker-compose up -d`
  2. .env file: `KAFKA_TOPIC=my-topic`
  3. Default: `orders`

### Partition Strategy and Consumer Scalability

**How Partitions Affect Consumption:**

```
Topic: order-events (3 partitions)
Consumer Group: order-service-group

Scenario 1: Single Consumer Instance
┌─────────────────────┐
│   Consumer 1        │
│   Reads: P0, P1, P2 │  ← Sequential processing
└─────────────────────┘
Throughput: 1x (baseline)

Scenario 2: Three Consumer Instances (Optimal)
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Consumer 1   │  │ Consumer 2   │  │ Consumer 3   │
│ Reads: P0    │  │ Reads: P1    │  │ Reads: P2    │
└──────────────┘  └──────────────┘  └──────────────┘
Throughput: 3x (parallel processing)

Scenario 3: Five Consumer Instances (Over-provisioned)
┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────┐  ┌──────────┐
│ Consumer 1   │  │ Consumer 2   │  │ Consumer 3   │  │ Consumer │  │ Consumer │
│ Reads: P0    │  │ Reads: P1    │  │ Reads: P2    │  │    4     │  │    5     │
└──────────────┘  └──────────────┘  └──────────────┘  │  IDLE    │  │  IDLE    │
                                                        └──────────┘  └──────────┘
Throughput: 3x (no improvement, wasted resources)
```

**Key Takeaways:**
- **Max Parallelism** = Number of Partitions (3 in our case)
- **Optimal Consumers** = Number of Partitions (1:1 mapping)
- **Over-provisioning** = Idle consumers (wasted resources)
- **Under-provisioning** = Some consumers handle multiple partitions (still works)

**Interaction with Message Keying:**
```
Producer: orderId as message key
   ↓
Kafka: Deterministic partitioning (hash-based)
   ↓
Consumer: Reads from assigned partition(s)
   ↓
Result: Same orderId always read by same consumer instance
```

**Ordering Guarantee:**
- **Within a consumer instance**: Messages from assigned partition(s) processed in order
- **Across consumer instances**: No global ordering (different partitions processed in parallel)
- **For same orderId**: Always same partition → same consumer → strict ordering

**Scalability Pattern:**
```
Low Traffic:
  1 consumer + 3 partitions = Works, but underutilized

Medium Traffic:
  2 consumers + 3 partitions = One handles 2 partitions, one handles 1

High Traffic:
  3 consumers + 3 partitions = Optimal, 1:1 mapping

Need More Throughput?
  Increase partitions to 6 → Deploy 6 consumers
  (Requires topic reconfiguration and consumer restart)
```

---

## Health & Monitoring

### Health Endpoint

#### Actuator Health Endpoint (Spring Boot)
- **Purpose**: Basic application health check (managed by Spring Boot Actuator)
- **Endpoint**: `GET http://localhost:8082/actuator/health`
- **Response**:
  ```json
  {
    "status": "UP"
  }
  ```
- **Note**: This is Spring Boot's default actuator endpoint. The consumer also provides custom health endpoints.

#### Custom Health Endpoints (OrderController)

The consumer implements two custom health endpoints for Kubernetes/Docker orchestration:

##### **Liveness Probe** (GET /order-service/health/live)
- **Purpose**: Docker/Kubernetes uses this to detect dead containers
- **Endpoint**: `http://localhost:8082/order-service/health/live`
- **Response**:
  ```json
  {
    "serviceName": "Order Service (Consumer)",
    "type": "liveness",
    "status": "UP",
    "timestamp": "2026-01-02T12:00:00.123456Z",
    "checks": {
      "service": {"status": "UP", "details": "Order Service is running and responsive"}
    }
  }
  ```
- **HTTP Status**: Always 200 OK (if service is running)
- **Used By**: Docker healthcheck in docker-compose.yml
- **Docker Config**: `test: [ "CMD", "curl", "-f", "http://localhost:8082/order-service/health/live" ]`

##### **Readiness Probe** (GET /order-service/health/ready)
- **Purpose**: Indicates if the service is ready to handle requests
- **Endpoint**: `http://localhost:8082/order-service/health/ready`
- **Checks**: Service status, Kafka connectivity, and local state store
- **HTTP Status**: 
  - 200 OK if ready (Kafka UP or DEGRADED)
  - 503 Service Unavailable if not ready (Kafka DOWN)
- **See Full Documentation**: Jump to "Health & Monitoring" section below for complete details

---

## Logging Configuration

### Root Logger

#### `logging.level.root=INFO`
- **Purpose**: Global logging level
- **Levels** (from most to least verbose):
  - `TRACE`: Extremely detailed (every variable assignment)
  - `DEBUG`: Detailed (method calls, state changes)
  - `INFO`: Important events (startup, major operations)
  - `WARN`: Warnings (potential issues)
  - `ERROR`: Errors (failures)
- **Why `INFO`**: Good balance for production (not too noisy)
- **Override**: `LOGGING_LEVEL_ROOT=DEBUG docker-compose up -d`

### Consumer Service Logger

#### `logging.level.mta.eda.consumer=DEBUG`
- **Purpose**: Enable detailed logging for consumer code
- **Why `DEBUG`**:
  - Shows message processing details
  - Logs idempotency checks, sequencing decisions
  - Essential for debugging state changes
  - Package-specific (doesn't affect other logs)
- **Example Logs**:
  ```
  DEBUG: Received message for orderId: ORD-001, partition: 0, offset: 42
  DEBUG: Duplicate event: Order ORD-001 already in state CONFIRMED. Skipping.
  DEBUG: Created order ORD-001. Status: NEW -> CREATED | Shipping Cost: $12.50
  ```

### Log Format (Spring Boot Default)
```
2026-01-02 12:00:00.000 INFO  [order-service] mta.eda.consumer.service.kafka.KafkaConsumerService
```

---

## Docker Compose Environment Variables

All Spring Boot properties can be overridden via environment variables using this convention:
```
spring.kafka.bootstrap-servers → SPRING_KAFKA_BOOTSTRAP_SERVERS
logging.level.root → LOGGING_LEVEL_ROOT
```

### docker-compose.yml Environment Section
```yaml
environment:
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
  SPRING_KAFKA_CONSUMER_GROUP_ID: order-service-group
  KAFKA_TOPIC: orders
  LOGGING_LEVEL_ROOT: INFO
  LOGGING_LEVEL_MTA_EDA_CONSUMER: DEBUG
```

**Note**: The actual docker-compose.yml uses `kafka:29092` (not `general:29092`) and port `8082` for the consumer.

### .env File Example
```bash
# Topic name - customize per deployment
KAFKA_TOPIC=orders

# Kafka broker address
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092

# Consumer group
SPRING_KAFKA_CONSUMER_GROUP_ID=order-service-group

# Logging
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_MTA_EDA_CONSUMER=DEBUG
```

### Runtime Overrides
```bash
# Single override
KAFKA_TOPIC=my-topic docker-compose up -d

# Multiple overrides
KAFKA_TOPIC=my-topic LOGGING_LEVEL_MTA_EDA_CONSUMER=TRACE docker-compose up -d

# From .env file
docker-compose --env-file .env.custom up -d
```

---

## Network Configuration

### Docker Network

#### `networks: ecommerce-network`
- **Purpose**: Connect consumer to producer's Kafka infrastructure
- **Type**: Bridge network (default Docker network driver)
- **DNS Resolution**: Docker provides internal DNS (hostname → IP)
- **How It Works**:
  - Consumer connects to `kafka:29092`
  - Docker DNS resolves `kafka` → producer's kafka container IP
  - Connection succeeds transparently
- **Network Definition**:
  ```yaml
  networks:
    ecommerce-network:
      driver: bridge
  ```

#### Producer's Network (Same Name)
- **Consumer**: `networks: ecommerce-network`
- **Producer**: `networks: ecommerce-network`
- **Requirement**: Both must use **same network name** to communicate

---

## Health Check Configuration

### Container Health Check
```yaml
healthcheck:
  test: [ "CMD", "curl", "-f", "http://localhost:8081/actuator/health" ]
  interval: 10s
  timeout: 5s
  retries: 5
```

- **Test Command**: Curl the health endpoint
- **Interval**: Check every 10 seconds
- **Timeout**: Fail if response takes >5 seconds
- **Retries**: Container marked unhealthy after 5 failed checks
- **Total Time to Unhealthy**: ~50 seconds (5 × 10s)

---

## HealthService Configuration

### Service Health Check
The `HealthService.getServiceStatus()` method checks if the Order Service is responsive.

- **Implementation**: Method invocation test - if callable, returns UP
- **Response**: `HealthCheck("UP", "Order Service is running and responsive")`
- **Failure**: `HealthCheck("DOWN", "Order Service is not responding: ...")`
- **Used By**: Both liveness and readiness probes

### Kafka Broker Health Check
The `HealthService.getKafkaStatus()` method verifies Kafka broker connectivity through `KafkaConnectivityService`.

- **Implementation**: 
  - **Fresh Ping**: Calls `pingKafka()` on every health check for current status
  - **No Retries**: Single attempt with 3-second timeout
  - **Cache Update**: Updates cached state if Kafka status changed
  - Returns the most current status (not stale cached data)
- **Response States**:
  - `HealthCheck("UP", "Connected and consuming from topics")` - Fully operational
  - `HealthCheck("DEGRADED", "Connected to broker but topic 'orders' not ready")` - Temporary issue
  - `HealthCheck("DOWN", "Cannot connect to Kafka broker at kafka:29092")` - Connection failed
- **Bootstrap Server**: Retrieved from `spring.kafka.bootstrap-servers` (kafka:29092 in Docker)
- **Used By**: Readiness probe only
- **Impact**: 
  - **Kafka UP or DEGRADED**: Readiness returns HTTP 200 OK (service ready)
  - **Kafka DOWN**: Readiness returns **HTTP 503 Service Unavailable** (service NOT ready)
- **Rationale**: Consumer cannot function without Kafka, so Kafka DOWN = service not ready
- **Orchestration**: Kubernetes/Docker will stop routing traffic to unhealthy consumer instances
- **Fresh Status Guarantee**: Ping mechanism ensures orchestrators always get current Kafka state

### Local State Store Health Check
The `HealthService.getLocalStateStatus()` method verifies the in-memory order state store.

- **Implementation**: Tests access to `ConcurrentHashMap<String, ProcessedOrder>`
- **Response**: `HealthCheck("UP", "Local order state store is accessible")`
- **Failure**: `HealthCheck("DOWN", "Local state store is unavailable: ...")`
- **Storage**: In-memory only (data lost on container restart)
- **Used By**: Readiness probe only

### Health Probe Logging
Both health probes include DEBUG level logging:

```java
@GetMapping("/health/live")
public ResponseEntity<HealthResponse> live() {
    logger.debug("Liveness probe called");  // ← DEBUG level
    // ...
}

@GetMapping("/health/ready")
public ResponseEntity<HealthResponse> ready() {
    logger.debug("Readiness probe called");  // ← DEBUG level
    // ...
}
```

**Visibility**:
- ✅ Visible if `LOGGING_LEVEL_MTA_EDA_CONSUMER: DEBUG` is set
- ❌ Hidden if only `LOGGING_LEVEL_ROOT: INFO` is set (DEBUG filtered out)

---

## Request Validation Configuration

### OrderDetailsRequest Validation
The `OrderDetailsRequest` DTO validates incoming order ID requests:

```java
public record OrderDetailsRequest(
    @NotBlank(message = "orderId is required and cannot be empty")
    @Pattern(
        regexp = "^[0-9A-Fa-f]+$",
        message = "orderId must be in hexadecimal format (0-9, A-F)"
    )
    @JsonProperty("orderId")
    String orderId
) {}
```

**Validation Annotations**:
- `@NotBlank`: Rejects null, empty, or whitespace-only values
  - Error Message: "orderId is required and cannot be empty"
  - HTTP Status: 400 Bad Request
- `@Pattern`: Validates hexadecimal format (0-9, A-F, case-insensitive)
  - Error Message: "orderId must be in hexadecimal format (0-9, A-F)"
  - HTTP Status: 400 Bad Request

**Valid Examples**:
```
ABC123
def456
FEDCBA
1234567890
```

**Invalid Examples**:
```
ABC-123 (contains dash)
XYZ123 (X, Y, Z not in hex)
G1234 (G not in hex)
(empty)
```

### AllOrdersFromTopicRequest Validation
The `AllOrdersFromTopicRequest` DTO validates topic name requests:

```java
public record AllOrdersFromTopicRequest(
    @NotBlank(message = "topicName is required")
    @JsonProperty("topicName")
    String topicName
) {}
```

**Validation Annotations**:
- `@NotBlank`: Ensures topic name is provided
  - Error Message: "topicName is required"
  - HTTP Status: 400 Bad Request

---

## Configuration Change Checklist

When modifying configuration:

- [ ] **Topic Name**: Update `KAFKA_TOPIC` in `.env` or docker-compose
- [ ] **Broker Address**: Update `SPRING_KAFKA_BOOTSTRAP_SERVERS` if moving Kafka
- **Do NOT change**:
  - [ ] `enable-auto-commit=false` (required for At-Least-Once)
  - [ ] `AckMode.MANUAL_IMMEDIATE` (required for reliability)
  - [ ] `auto-offset-reset` (set to `earliest` by design)

---

## Troubleshooting Configuration Issues

### Consumer Won't Connect to Kafka
- **Check**: `SPRING_KAFKA_BOOTSTRAP_SERVERS` matches broker address
- **Docker**: Ensure producer's docker-compose is running first
- **Logs**: `docker-compose logs order-service | grep -i "kafka\|connection"`

### Messages Not Being Processed
- **Check**: Topic name matches producer (`KAFKA_TOPIC`)
- **Check**: Consumer group hasn't already consumed those messages (use new group-id)
- **Reset**: Change `auto-offset-reset=latest` temporarily to see only new messages

### Health Check Failing
- **Check**: Service is running on port **8082** (not 8081)
- **Check**: Spring Boot startup completed (takes 10-15 seconds)
- **Check**: Liveness probe endpoint: `http://localhost:8082/order-service/health/live`
- **Override**: Increase `start_period` in docker-compose healthcheck (currently 20s)
- **Common Issue**: Kafka unavailable makes readiness fail (expected); liveness should still pass

### Too Much/Not Enough Logging
- **Increase**: `LOGGING_LEVEL_MTA_EDA_CONSUMER=TRACE`
- **Decrease**: `LOGGING_LEVEL_ROOT=WARN`
- **Target Package**: `LOGGING_LEVEL_mta_eda_consumer_service_order=DEBUG`

------

## Docker Deployment

### Deployment Modes

#### Standalone Mode (Without Kafka)
```bash
docker compose up
```
- **Purpose**: Testing Consumer API independently
- **Network**: Uses default Docker network + external producer network (if available)
- **Kafka Connection**: Attempts to connect; fails gracefully if Kafka unavailable (admin.fail-fast=false)
- **Health Check**: Shows Kafka as DOWN when not connected
- **API**: Fully functional on port 8082
- **Message Consumption**: Disabled when Kafka unavailable
- **Auto-Recovery**: KafkaConnectivityService monitors and auto-reconnects when Kafka becomes available

#### Integrated Mode (With Producer's Kafka) - RECOMMENDED
```bash
# Step 1: Start Producer first (includes Kafka and Zookeeper)
cd ../producer && docker compose up -d

# Step 2: Start Consumer (automatically connects to producer network)
cd ../consumer && docker compose up -d
```
- **Network**: Automatically joins `producer_ecommerce-network` (configured in docker-compose.yml)
- **Kafka Connection**: Resolves `kafka:29092` via Docker DNS
- **Health Check**: Shows Kafka as UP once connected
- **Message Consumption**: Enabled automatically
- **No Manual Network Connection Needed**: The docker-compose.yml includes the external network configuration

**Why This Works:**
The consumer's docker-compose.yml declares:
```yaml
networks:
  - default              # Consumer's own network
  - producer-net         # External network from producer

networks:
  producer-net:
    external: true
    name: producer_ecommerce-network
```
This allows the consumer to connect to both its own default network and the producer's external network without manual intervention.

### Network Configuration
- **Standalone**: Uses default Docker network only
- **Integrated**: Uses default Docker network + `producer_ecommerce-network` external network
- **Kafka Service Name**: `kafka` (resolves to Kafka broker in Producer network)
- **Kafka Port**: `29092` (internal Docker network port)
- **DNS Resolution**: Docker provides internal DNS for container name resolution

### Service Ports
- **Order Service**: `8082` (Consumer API)

### Environment Variables (docker-compose.yml)
- `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092` - Kafka broker address (overrides `KAFKA_BOOTSTRAP_SERVERS` default)
  - **Note**: Spring Boot automatically converts `SPRING_KAFKA_BOOTSTRAP_SERVERS` to `spring.kafka.bootstrap-servers`
  - Application.properties uses `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, but Docker uses the Spring Boot convention
  - Both work: Spring Boot handles the property name transformation
- `SPRING_KAFKA_CONSUMER_GROUP_ID=order-service-group` - Consumer group ID
- `KAFKA_TOPIC=orders` - Topic to consume from
- `LOGGING_LEVEL_ROOT=INFO` - Root logging level
- `LOGGING_LEVEL_MTA_EDA_CONSUMER=DEBUG` - Application debug logs

### Kafka Listener Behavior
- **autoStartup=true** in KafkaConsumerConfig allows Consumer to start immediately
- Listeners attempt to connect on startup
- If Kafka is unavailable, Spring logs errors but application stays up
- KafkaConnectivityService monitors health status independently with exponential backoff
- Enables faster startup and immediate consumption when Kafka is ready
- Auto-recovery: Reconnects automatically when Kafka becomes available

---

## Status Machine (State Validation)

### Overview
The consumer enforces a **state machine** to validate order status transitions, preventing invalid state changes and maintaining data consistency.

**Implementation Location**: `consumer/src/main/java/mta/eda/consumer/service/util/StatusMachine.java`

### Allowed Status Progression

```
NEW (0) → CONFIRMED (1) → DISPATCHED (2) → COMPLETED (3)
                        ↓
                    CANCELED (4) [terminal - reachable from any state]
```

### Status Definitions

| Status | Order | Description | Terminal? |
|--------|-------|-------------|-----------|
| NEW | 0 | Order just created | No |
| CONFIRMED | 1 | Customer confirmed order | No |
| DISPATCHED | 2 | Order sent to shipping | No |
| COMPLETED | 3 | Order delivered | Yes |
| CANCELED | 4 | Order canceled (any time) | Yes |

### Validation Rules

#### ✅ **Valid Transitions**

1. **Strictly Sequential Forward Progression**
   - NEW → CONFIRMED (0 → 1) ✅
   - CONFIRMED → DISPATCHED (1 → 2) ✅
   - DISPATCHED → COMPLETED (2 → 3) ✅
   - **Note**: You **MUST** progress one step at a time. Skipping states is NOT allowed.
   - **Example**: NEW → DISPATCHED is **INVALID** ❌ (must go NEW → CONFIRMED → DISPATCHED)
   - **Example**: CONFIRMED → COMPLETED is **INVALID** ❌ (must go CONFIRMED → DISPATCHED → COMPLETED)

2. **Cancellation (Terminal State)**
   - NEW → CANCELED ✅
   - CONFIRMED → CANCELED ✅
   - DISPATCHED → CANCELED ✅
   - **Note**: CANCELED is a terminal state reachable from any non-terminal state
   - **Exception**: COMPLETED → CANCELED is **NOT allowed** (COMPLETED is also terminal)

#### ❌ **Invalid Transitions**

1. **Backward Progression**
   - COMPLETED → DISPATCHED ❌
   - DISPATCHED → CONFIRMED ❌
   - CONFIRMED → NEW ❌

2. **Unknown Statuses**
   - Any unknown status → Rejected ❌
   - Only: NEW, CONFIRMED, DISPATCHED, COMPLETED, CANCELED allowed

### Implementation Details

#### Consumer Processing Flow

```
┌──────────────────────────────────────┐
│ 1. Receive order from Kafka          │
│    orderId: ORD-123                  │
│    status: "confirmed"               │
└───────────┬──────────────────────────┘
            ↓
┌──────────────────────────────────────┐
│ 2. Check idempotency (exact match)   │
│    Current: CONFIRMED                │
│    Incoming: CONFIRMED               │
│    → Skip (duplicate)                │
└───────────┬──────────────────────────┘
            ↓
┌──────────────────────────────────────┐
│ 3. Validate state transition         │
│    Using StatusMachine               │
│    Current: NEW, Incoming: CONFIRMED │
│    Check: 1 >= 0? YES → Valid        │
└───────────┬──────────────────────────┘
            ↓
┌──────────────────────────────────────┐
│ 4. Process order                     │
│    Calculate shipping cost           │
│    Update internal store             │
└───────────┬──────────────────────────┘
            ↓
┌──────────────────────────────────────┐
│ 5. Manual acknowledgment             │
│    Only if all previous steps pass   │
└──────────────────────────────────────┘
```

#### Code Integration

In `OrderService.processOrder()`:

```java
// Validate status transition using state machine
String currentStatus = current == null ? null : current.order().status();
if (!StatusMachine.isValidTransition(currentStatus, order.status())) {
    logger.warn("⊘ Invalid Status Transition: Order {} cannot transition from '{}' to '{}'", 
                orderId, currentStatus, order.status());
    return; // Skip invalid message
}
```

### Logging Examples

**Valid Transition:**
```
✓ Successfully processed order: orderId=ORD-123, status=CONFIRMED
✓ Updated order ORD-123. Status: NEW → CONFIRMED
```

**Duplicate Detection (Same Status):**
```
⊘ Idempotency Check: Order ORD-123 already in state 'CONFIRMED'. Skipping duplicate.
```

**Invalid Transition:**
```
⊘ Invalid Status Transition: Order ORD-123 cannot transition from 'DISPATCHED' to 'NEW'. Skipping.
```

### Behavior on Invalid Messages

1. **Idempotency Check Fails** (Exact duplicate):
   - Log: "Idempotency Check: ... Skipping duplicate"
   - Action: Skip processing
   - State: Unchanged
   - Acknowledge: Yes (prevents redelivery)

2. **State Transition Fails** (Invalid transition):
   - Log: "Invalid Status Transition: ... Skipping"
   - Action: Skip processing
   - State: Unchanged
   - Acknowledge: Yes (prevents redelivery)

3. **Both Checks Pass** (Valid new state):
   - Log: "Successfully processed order: ..."
   - Action: Update internal state
   - State: Changed to new status
   - Acknowledge: Yes (advances offset)

### Debug Endpoint

To view all processed messages (which reflect valid state transitions):

```bash
curl http://localhost:8082/order-service/debug/all-messages
```

Response shows only messages that passed validation:
```json
{
  "totalMessages": 5,
  "messages": [
    {"time": "2026-01-04T10:00:00Z", "orderId": "ORD-123", "status": "new"},
    {"time": "2026-01-04T10:01:00Z", "orderId": "ORD-123", "status": "confirmed"},
    {"time": "2026-01-04T10:02:00Z", "orderId": "ORD-123", "status": "dispatched"},
    ...
  ]
}
```

---

## Best Practices

1. **Use `.env` file for customization** instead of modifying docker-compose.yml
2. **Keep `enable-auto-commit=false`** - this is critical for safety
3. **Use meaningful consumer group IDs** - makes debugging easier
4. **Set `auto-offset-reset=earliest`** - prevents data loss on restart
5. **Use `MANUAL_IMMEDIATE` for health** - synchronous and predictable
6. **Enable debug logging** for the consumer package during development
7. **Use health checks** for production deployment verification
