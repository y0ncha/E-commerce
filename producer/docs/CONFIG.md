# Configuration Overview

Complete reference for all configuration properties in the Producer (Cart Service).

This document explains the Kafka-specific configurations that enable **strict ordering**, **reliability**, and **resilience** as required by Exercise 2.

---

## Kafka Configuration Strategy

The Producer is configured to satisfy three critical requirements:

1. **Strict Ordering**: Events for the same order must be processed in sequence
   - **Solution**: Use `orderId` as the message key for partition affinity
   
2. **At-Least-Once Delivery**: No order events should be lost
   - **Solution**: `acks=all`, `enable.idempotence=true`, retry configuration
   
3. **Resilience**: Handle broker unavailability gracefully
   - **Solution**: Circuit Breaker pattern, health monitoring, retry with exponential backoff

---

## Application Settings

### Server
- `spring.application.name=producer`
- `server.port=8081` - Producer API port (Cart Service)

### Logging
- `logging.level.root=INFO` - Default log level
- `logging.level.mta.eda.producer=DEBUG` - Application logs
- `logging.level.org.apache.kafka.clients.admin.AdminMetadataManager=WARN` - Suppress bootstrap spam
- `logging.level.org.apache.kafka.clients=WARN` - Suppress Kafka client verbosity
- `logging.level.org.apache.kafka.common.network=WARN` - Suppress network logs
- `logging.level.org.springframework.kafka=WARN` - Suppress Spring Kafka logs

---

## Kafka Connection

### Bootstrap Servers
- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
  - **Default:** `localhost:9092` (local development)
  - **Docker:** Set `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` via environment variable
  - **Purpose:** Initial connection to Kafka cluster
  - **Network:** Kafka runs on `producer_ecommerce-network` bridge network

### Admin Client
- `spring.kafka.admin.fail-fast=false` - Don't crash app if Kafka is down on startup
- `spring.kafka.admin.properties.allow.auto.create.topics=false` - Disable auto topic creation
- `spring.kafka.properties.request.timeout.ms=5000` - Admin API request timeout (5s)
- `spring.kafka.properties.default.api.timeout.ms=5000` - Admin API default timeout (5s)

---

## Producer Configuration

### Serialization
- `spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer`
  - **Key format:** `orderId` as String (e.g., "ORD-1A2B")
- `spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer`
  - **Value format:** Order object serialized as JSON
- `spring.kafka.producer.client-id=eda-producer` (default, configurable)

### Durability & Reliability
- `spring.kafka.producer.acks=all`
  - **Purpose:** Wait for all in-sync replicas to acknowledge
  - **Guarantees:** Strongest durability (no data loss if at least 1 replica alive)
- `spring.kafka.producer.properties.enable.idempotence=true`
  - **Purpose:** Prevent duplicate messages on retry
  - **Guarantees:** Exactly-once semantics per partition
  - **Note:** Automatically sets `acks=all`, `retries=MAX_INT`, `max.in.flight.requests=5`

### Retry Configuration
- `spring.kafka.producer.retries=2147483647` (Integer.MAX_VALUE)
  - **Purpose:** Number of times to retry failed sends on transient errors
  - **Set to MAX_INT** to allow Kafka to retry until delivery.timeout.ms is reached
  - **Safe:** Idempotence enabled prevents duplicate messages on retries
- `spring.kafka.producer.properties.retry.backoff.ms=100`
  - **Purpose:** Initial backoff delay before first retry
  - **Strategy:** Exponential backoff (Kafka multiplies by 2 between retries)
  - **Retry behavior:** Retries until `delivery.timeout.ms` (8000ms) is reached
  - **Exponential backoff pattern:** 100ms → 200ms → 400ms → 800ms → 1.6s → 3.2s → ...
  - **Total retry window:** ~8 seconds (delivery timeout limit)
  - **Note:** With MAX_INT retries, the actual limit is controlled by delivery.timeout.ms

### Ordering Guarantees
- `spring.kafka.producer.properties.max.in.flight.requests.per.connection=1`
  - **Purpose:** Ensures strict ordering by allowing only 1 unacknowledged request at a time
  - **Trade-off:** Lower throughput but guarantees absolute ordering within a partition
  - **Key-based partitioning:** Messages with same key (orderId) go to same partition in order
  - **Note:** More restrictive than the default (5 with idempotence) for stronger ordering guarantees

### Performance Tuning
- `spring.kafka.producer.properties.batch.size=16384` (16 KB)
  - **Purpose:** Max size of batched messages before sending
  - **Trade-off:** Higher = better throughput, lower = lower latency
- `spring.kafka.producer.properties.linger.ms=5`
  - **Purpose:** Artificial delay to accumulate batches (5ms for high-volume throughput)
  - **Trade-off:** Higher values improve throughput by batching, 0 minimizes latency
- `spring.kafka.producer.properties.compression.type=snappy`
  - **Purpose:** Compress messages before sending
  - **Options:** none, gzip, snappy, lz4, zstd
  - **Trade-off:** CPU usage vs network bandwidth

### Timeouts
- `spring.kafka.producer.properties.request.timeout.ms=3000` (3s)
  - **Purpose:** Max time to wait for broker response per request attempt
- `spring.kafka.producer.properties.delivery.timeout.ms=8000` (8s)
  - **Purpose:** Upper bound for entire send operation (including all retries)
  - **Ensures:** Kafka client stops retrying before API timeout (10s) to prevent ghost successes
  - **Formula:** Must be >= `linger.ms + request.timeout.ms`
  - **Validation:** `8000 >= 5 + 3000 = 3005` ✅
- `spring.kafka.producer.properties.max.block.ms=5000` (5s)
  - **Purpose:** Max time to block waiting for metadata/buffer space
- `producer.send.timeout.ms=10000` (10s, custom property)
  - **Purpose:** Timeout for `.send().get()` calls in application code
  - **Synchronous Retry Strategy:** API waits up to 10s, but Kafka stops at 8s to ensure no ghost successes
  - **Location:** `KafkaProducerService.java`

---

## Topic Configuration

- `kafka.topic.name=orders`
  - **Purpose:** Topic name for all order events
  - **Critical:** Must match consumer topic name for message delivery to work
  - **Note:** Both producer and consumer must use the same topic: "orders"
  
- `kafka.topic.partitions=3`
  - **Purpose:** Number of partitions for parallel processing
  - **Course Requirement:** Multiple partitions demonstrate message keying importance
  - **Scalability:** Enables parallel consumption by multiple consumer instances
  - **Ordering Guarantee:** Each partition maintains strict FIFO ordering
  - **Recommendation:** >= number of consumer instances for optimal parallelism
  - **Trade-off:** 
    - More partitions = higher throughput + more parallelism
    - More partitions = higher resource overhead (broker memory, file handles)
  
- `kafka.topic.replication-factor=1`
  - **Purpose:** Number of replicas per partition
  - **Current Setup:** 1 (Single broker development environment)
  - **Production Recommendation:** 3 replicas for high availability
  - **Durability:** With `acks=all`, producer waits for all replicas to acknowledge
  - **Note:** In production, use replication-factor >= 3 to survive broker failures

### Message Keying and Partitioning Strategy

**The Core Pattern:**
```
orderId (Message Key) → Kafka Partitioning → Same Partition → Ordered Processing
```

**How It Works:**

1. **Producer Side:**
   ```java
   kafkaTemplate.send(topicName, orderId, order)
                           //    ↑ Message Key
   ```

2. **Kafka Partitioning Algorithm:**
   ```
   partition = murmur2(orderId) % num_partitions
   ```
   - Uses MurmurHash2 algorithm (deterministic hash function)
   - Same orderId always produces the same hash value
   - Hash modulo num_partitions determines target partition

3. **Partition Assignment:**
   ```
   Example with 3 partitions:
   
   ORD-001 → hash=12345 → 12345 % 3 = 0 → Partition 0
   ORD-001 → hash=12345 → 12345 % 3 = 0 → Partition 0 (always!)
   ORD-002 → hash=67890 → 67890 % 3 = 2 → Partition 2
   ORD-002 → hash=67890 → 67890 % 3 = 2 → Partition 2 (always!)
   ```

4. **Ordering Guarantee:**
   - All messages with key="ORD-001" land in Partition 0
   - Partition 0 maintains FIFO order: offset 10, 11, 12, ...
   - Consumer reading Partition 0 receives messages in exact write order
   - **Result:** ORD-001 events are processed in sequence (CREATED → CONFIRMED → DISPATCHED)

**Why Multiple Partitions with Message Keying:**

| Aspect | Single Partition | Multiple Partitions with Keys |
|--------|------------------|------------------------------|
| **Ordering** | Global ordering (all messages) | Per-key ordering (messages with same key) |
| **Throughput** | Limited by single partition write speed | Scales linearly with partitions |
| **Parallelism** | Only 1 consumer can read | Multiple consumers can read (1 per partition) |
| **Scalability** | Cannot scale beyond 1 consumer | Scales to N consumers (N = partitions) |
| **Use Case** | When global order is required | When per-entity order is sufficient (our case) |

**Our Scenario:**
- **Requirement:** Order ORD-001's events must be in order
- **Not Required:** ORD-001 and ORD-002 events to be in global order
- **Solution:** 3 partitions + orderId as key
  - ORD-001 events → Partition 0 (ordered)
  - ORD-002 events → Partition 2 (ordered)
  - ORD-003 events → Partition 1 (ordered)
  - **Result:** 3x throughput, per-order ordering maintained

**Interaction with Consumer:**
```
Producer (3 partitions)          Consumer (3 instances)
  ↓                                ↓
Partition 0 (ORD-001, ORD-004) → Consumer Instance 1
Partition 1 (ORD-003, ORD-006) → Consumer Instance 2
Partition 2 (ORD-002, ORD-005) → Consumer Instance 3
```
- Each consumer instance reads from 1 partition
- Within each instance, messages are processed in order
- Different orders processed in parallel across instances
- **Horizontal Scalability:** Add more partitions + consumers for higher throughput

---

## Producer Configuration - KafkaTemplate

The Producer uses Spring's `KafkaTemplate` for message publishing:

```java
@Bean
public KafkaTemplate<String, Order> kafkaTemplate(ProducerFactory<String, Order> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
}
```

**Key Features:**
- **Type Safety:** `<String, Order>` ensures compile-time type checking
  - String = orderId (message key)
  - Order = order object (message value)
- **Serialization:** Automatically serializes Order objects to JSON
- **Synchronous Send:** `.send().get(timeout)` blocks until ACK or timeout
- **Error Handling:** Throws exceptions on failure (caught by Circuit Breaker)

**Usage in KafkaProducerService:**
```java
SendResult<String, Order> result = kafkaTemplate.send(topicName, orderId, order)
    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
```

---

## Health Check Configuration

- `management.endpoints.web.exposure.include=health`
  - **Purpose:** Expose health endpoint via Actuator
- `management.endpoint.health.show-details=always`
  - **Purpose:** Always show detailed health check results
- `management.endpoint.health.probes.enabled=true`
  - **Purpose:** Enable Kubernetes liveness/readiness probes

### Health Check Behavior
- **Background Monitoring:** `KafkaConnectivityService` continuously monitors Kafka with exponential backoff
  - Initial retry: 100ms → 200ms → 400ms → 800ms → 1.6s → 3.2s → 5s (max)
  - Healthy state: checks every 30 seconds
  - Connection lost: immediately switches to aggressive retry mode
- **Fresh Ping on Health Checks:** Each health endpoint call triggers a fresh Kafka ping (3s timeout, no retries)
  - Updates cached status if Kafka state changed
  - Ensures health status reflects current state, not stale data

### Health Endpoints
- **GET `/cart-service/health/live`**
  - Always returns 200 OK (checks service responsiveness only)
  - No Kafka dependency
  - Used by orchestrators to detect if service process is alive
- **GET `/cart-service/health/ready`**
  - **Fresh Status Check:** Pings Kafka before responding (single attempt, 3s timeout)
  - Updates cached status if Kafka state changed since last check
  - Returns 200 OK if Kafka reachable AND topic exists
  - Returns 503 Service Unavailable if Kafka down or topic not found
  - Fast response (~3 seconds max for ping)
  - Used by load balancers to route traffic only to healthy instances

---

## Kafka Connectivity Monitoring

The `KafkaConnectivityService` provides **continuous background monitoring** of Kafka broker and topic availability with sophisticated resilience patterns.

### Features
- **Asynchronous Background Monitoring**: Non-blocking, runs in dedicated thread
- **Exponential Backoff Retry**: Powered by Resilience4j with intelligent retry intervals
- **Pre-cached Status**: Instant health check responses (no blocking API calls)
- **Persistent Retry**: Never gives up - continuously retries until connection restored
- **Intelligent Error Detection**: Two-pass approach to distinguish between:
  - **KAFKA_DOWN**: Connection/timeout errors (broker unreachable)
  - **TOPIC_NOT_FOUND**: Topic doesn't exist (broker reachable but topic missing)
- **Topic Detection**: Verifies topic exists and has ready partitions with leaders

### Retry Strategy
The service uses **exponential backoff** with the following characteristics:

**Initial Phase (Fast Recovery):**
- Starts at **100ms** for aggressive first retries
- Multiplier: **2x** exponential
- Retry sequence: 100ms → 200ms → 400ms → 800ms → 1.6s → 3.2s → 5s
- **Max interval capped at 5 seconds** to keep reconnects responsive

**Steady State:**
- Once connected and topic ready: checks every **30 seconds**
- If connection lost: immediately switches back to aggressive retry mode
- If topic missing: keeps checking every **1 second**

### Error Detection Strategy
The `KafkaConnectivityService` uses a **two-pass detection approach** to accurately classify errors:

**Pass 1 - Rule Out Connection Issues:**
- Checks for `TimeoutException`, `IOException`, connection refused errors
- If found with timeout/connection messages → Returns `KAFKA_DOWN`
- Prevents false positives where metadata errors are mistaken for topic issues

**Pass 2 - Check for Topic Issues:**
- Only after ruling out connection problems
- Checks for `UnknownTopicOrPartitionException`
- If found → Returns `TOPIC_NOT_FOUND`

**Why This Matters:**
- **Accurate Diagnostics**: Operators know if it's infrastructure (Kafka down) vs. configuration (topic missing)
- **Correct HTTP Status**: 
  - KAFKA_DOWN → 503 (temporary, retry later)
  - TOPIC_NOT_FOUND → 500 (configuration error, needs admin action)
- **Better Monitoring**: Alerts can distinguish between transient and persistent issues

### Health Check Ping Mechanism
When health endpoints are called, the service performs a **fresh ping** to Kafka to ensure status is current:

**How It Works:**
1. **On-Demand Check**: When `/health/ready` is called, `pingKafka()` executes
2. **Single Attempt**: No retries, just one quick connectivity test (3 second timeout)
3. **Status Update**: If Kafka state changed (e.g., went from UP to DOWN), cache is updated immediately
4. **Return Fresh Status**: Health endpoint returns the most current status

**Benefits:**
- **No Stale Data**: Health checks reflect actual current state, not cached state from 30s ago
- **Fast Detection**: Status changes detected immediately when health endpoint is called
- **Orchestrator-Friendly**: Kubernetes/Docker get accurate readiness immediately
- **No Overhead**: Only pings when health endpoint is called, not continuously

**Example Scenario:**
```
1. Background monitor checks Kafka at 10:00:00 → Status: UP
2. Kafka crashes at 10:00:15
3. Health check called at 10:00:20:
   - pingKafka() detects Kafka is DOWN (fresh check)
   - Updates cache: kafkaConnected = false
   - Returns 503 Service Unavailable immediately
4. No need to wait for next background check (which would be at 10:00:30)
```

**Implementation:**
```java
// HealthService.java
public HealthCheck getKafkaStatus() {
    // Ping Kafka to get fresh status (updates cache if changed)
    kafkaConnectivityService.pingKafka();
    
    // Return current status (potentially just updated by ping)
    boolean healthy = kafkaConnectivityService.isHealthy();
    String details = kafkaConnectivityService.getDetailedStatus();
    return new HealthCheck(healthy ? "UP" : "DOWN", details);
}
```

### Topic Auto-Creation Mechanism
The `KafkaTopicConfig` class defines a `NewTopic` bean that automatically creates the topic on application startup:

```java
@Bean
public NewTopic orderEventsTopic(
    @Value("${kafka.topic.name}") String topicName,
    @Value("${kafka.topic.partitions:3}") int partitions,
    @Value("${kafka.topic.replication-factor:1}") short replicationFactor
) {
    return TopicBuilder.name(topicName)
            .partitions(partitions)
            .replicas(replicationFactor)
            .build();
}
```

**Behavior:**
- On startup, Spring's `KafkaAdmin` checks if the topic exists
- If missing, automatically creates it with configured partitions and replication factor
- If you delete the topic while the app is running, it may be recreated on next health check

**Why This Exists:**
- Simplifies development setup (no manual topic creation needed)
- Ensures consistent topic configuration across environments
- Topic is ready before any messages are published

**To Disable for Testing:**
Comment out the `@Bean` method in `KafkaTopicConfig.java` if you need to test missing-topic error scenarios.

### Status Detection
The service maintains three atomic flags:
- `kafkaConnected`: Broker is reachable and responding
- `topicReady`: Topic exists and all partitions have leaders
- `topicNotFound`: Specifically detected that topic doesn't exist (vs. other errors)

### Integration with Health Checks
- Health endpoints query these cached flags (instant response)
- No blocking I/O during health check requests
- Real-time status updates via background monitoring thread

---

## Status Machine (State Validation)

### Overview
The producer enforces a **state machine** to validate order status transitions before sending messages to Kafka, preventing invalid orders from being published.

**Implementation Location**: `producer/src/main/java/mta/eda/producer/service/util/StatusMachine.java`

### Allowed Status Progression

```
NEW (0) → CONFIRMED (1) → DISPATCHED (2) → COMPLETED (3)
                        ↓
                    CANCELED (4) [terminal - reachable from any state]
```

### Validation Rules

#### ✅ **Valid Transitions**

1. **Forward Progression**
   - NEW → CONFIRMED (0 → 1) ✅
   - CONFIRMED → DISPATCHED (1 → 2) ✅
   - DISPATCHED → COMPLETED (2 → 3) ✅

2. **Cancellation (Terminal State)**
   - NEW → CANCELED ✅
   - CONFIRMED → CANCELED ✅
   - DISPATCHED → CANCELED ✅
   - COMPLETED → CANCELED ✅ (even completed can be canceled)

#### ❌ **Invalid Transitions**

1. **Backward Progression**
   - COMPLETED → DISPATCHED ❌
   - DISPATCHED → CONFIRMED ❌
   - CONFIRMED → NEW ❌

2. **Unknown Statuses**
   - Any unrecognized status ❌

### Exception Handling

On invalid transition, `updateOrder()` throws `InvalidStatusTransitionException`:

```java
if (!StatusMachine.isValidTransition(existingOrder.status(), request.status())) {
    throw new InvalidStatusTransitionException(orderId, currentStatus, newStatus);
}
```

**Client Response (400 Bad Request):**
```
Invalid status transition for order ORD-123: DISPATCHED → NEW. 
Status can only move forward or to CANCELED.
```

### Shared Validation with Consumer

**Critical:** Producer and consumer use identical StatusMachine logic:

- **Producer**: Validates BEFORE publishing (prevents invalid messages)
- **Consumer**: Validates on receipt (defense-in-depth fallback)

**Result:** Guaranteed consistency across entire system

### Only Valid Orders Published

```
Kafka Topic: orders
├─ ORD-123, status=NEW (initial)
├─ ORD-123, status=CONFIRMED (valid transition: 0→1)
├─ ORD-123, status=DISPATCHED (valid transition: 1→2)
├─ ORD-456, status=NEW (different order)
├─ ORD-456, status=CONFIRMED (valid transition: 0→1)
└─ ORD-123, status=CANCELED (valid from any state)

❌ Invalid transitions NEVER reach Kafka
```

---

## Message Flow

### Key Assignment
```
Message Key: orderId (e.g., "ORD-1A2B")
  ↓
Partition Selection: hash(orderId) % num_partitions
  ↓
All events for same orderId → Same partition → Guaranteed ordering
```

### Payload Format
```json
{
  "orderId": "ORD-1A2B",
  "customerId": "CUST-5678",
  "orderDate": "2025-12-29T10:30:00Z",
  "items": [...],
  "totalAmount": 59.98,
  "status": "new"
}
```

---

## Retry Mechanism Summary

1. **Application sends** message via `KafkaProducerService.sendOrder()`
2. **Kafka client attempts** send with configured settings:
   - If fails: retry continuously with exponential backoff until 8s delivery timeout
     - Initial delays: 100ms → 200ms → 400ms → 800ms → 1.6s → 3.2s → ...
   - Idempotence prevents duplicates
   - Max 8s total delivery timeout (less than 10s API timeout to prevent ghost successes)
3. **Application blocks** on `.get(10000)` waiting for result (max 10s)
4. **On success:** Return 201/200 to client
5. **On timeout/failure:** 
   - Kafka client has already stopped retrying (at 8s)
   - Throws `ServiceUnavailableException` or `TopicNotFoundException`
   - Returns 500 or 503 to client

**No manual retry loops** - all handled by Kafka client internally with exponential backoff.
**Ghost Success Prevention:** Delivery timeout (8s) < API timeout (10s) ensures client knows true outcome.

---

## Environment Profiles

### Default (Local Development)
```bash
# No profile needed
java -jar producer.jar
# Uses: localhost:9092
```

### Docker
```bash
SPRING_PROFILES_ACTIVE=docker
KAFKA_BOOTSTRAP_SERVERS=general:9092
# Uses: general:9092 (container network)
```

---

## File Locations

### Configuration Files
- `src/main/resources/application.properties` - All property definitions

### Java Configuration Classes
- `src/main/java/mta/eda/producer/config/KafkaProducerConfig.java` - Producer factory & template beans
- `src/main/java/mta/eda/producer/config/KafkaTopicConfig.java` - Topic creation & configuration

### Service Classes
- `src/main/java/mta/eda/producer/service/kafka/KafkaProducerService.java` - Message publishing
- `src/main/java/mta/eda/producer/service/kafka/KafkaHealthService.java` - Health checks
- `src/main/java/mta/eda/producer/controller/OrderController.java` - REST endpoints

---

## Docker Deployment

### Network Configuration
- **Network Name**: `producer_ecommerce-network`
- **Type**: Bridge network
- **Purpose**: Shared network for Zookeeper, Kafka, and Cart Service
- **Consumer Access**: Consumer can join this network to access Kafka

### Service Ports
- **Kafka External**: `9092` (host machine access)
- **Kafka Internal**: `29092` (Docker network access)
- **Cart Service**: `8081` (Producer API)

### Kafka Advertised Listeners
```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
```
- `kafka:29092` - For services within Docker network (Consumer uses this)
- `localhost:9092` - For host machine access (development/testing)

### Environment Variables (docker-compose.yml)
- `SPRING_PROFILES_ACTIVE=docker` - Activates Docker profile
- `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` - Connect via internal network
- `KAFKA_TOPIC=orders` - Default topic name
- `LOGGING_LEVEL_ROOT=INFO` - Root logging level
- `LOGGING_LEVEL_MTA_EDA_PRODUCER=DEBUG` - Application debug logs

---

## Quick Reference

| Property Category | Key Settings |
|-------------------|--------------|
| **Durability** | `acks=all`, `enable.idempotence=true` |
| **Ordering** | `max.in.flight.requests=1`, key-based partitioning |
| **Retries** | `retries=MAX_INT`, exponential backoff until 8s timeout |
| **Latency** | `linger.ms=5` (batch for throughput) |
| **Throughput** | `batch.size=16384`, `compression.type=snappy` |
| **Timeouts** | `request=3s`, `delivery=8s`, `api=10s` |
| **Health** | Background monitoring with fresh ping, 3s timeout |
