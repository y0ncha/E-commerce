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
- `spring.kafka.producer.retries=12`
  - **Purpose:** Number of times to retry failed sends on transient errors
  - **Increased from 3 to 12** for better network partition handling
  - **Safe:** Idempotence enabled prevents duplicate messages on retries
- `spring.kafka.producer.properties.retry.backoff.ms=100`
  - **Purpose:** Initial backoff delay before first retry
  - **Strategy:** Exponential backoff (Kafka multiplies by 2 between retries)
  - **Retry sequence (actual delays):**
    ```
    Attempt 1: 0ms (immediate)
    Retry 1:   100ms
    Retry 2:   200ms (100 * 2^1)
    Retry 3:   400ms (100 * 2^2)
    Retry 4:   800ms
    Retry 5:   1.6s
    Retry 6:   3.2s
    Retry 7:   6.4s
    Retry 8:   12.8s
    Retry 9:   25.6s
    Retry 10:  51.2s
    Retry 11:  102.4s (capped before delivery timeout)
    ```
  - **Total retry window:** ~60 seconds (covers most network partition recovery)
  - **Validation:** Fits comfortably within `delivery.timeout.ms=120000`

### Ordering Guarantees
- `max.in.flight.requests.per.connection` - **Not explicitly set** (defaults to 5 with idempotence)
  - **With idempotence enabled:** Up to 5 in-flight requests while preserving order
  - **Key-based partitioning:** Messages with same key (orderId) go to same partition in order

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
- `spring.kafka.producer.properties.request.timeout.ms=5000` (5s)
  - **Purpose:** Max time to wait for broker response per request
- `spring.kafka.producer.properties.delivery.timeout.ms=120000` (120s)
  - **Purpose:** Upper bound for entire send operation (including retries)
  - **Formula:** Must be > `request.timeout.ms * (retries + 1) + linger.ms`
  - **Validation:** `120000 > 5000 * 4 + 5 = 20005` ✅
- `spring.kafka.producer.properties.max.block.ms=5000` (5s)
  - **Purpose:** Max time to block waiting for metadata/buffer space
- `producer.send.timeout.ms=10000` (10s, custom property)
  - **Purpose:** Timeout for `.send().get()` calls in application code
  - **Location:** `KafkaProducerService.java`

---

## Topic Configuration

- `kafka.topic.name=order-events`
  - **Purpose:** Topic name for all order events
  - **Why This Name:** Descriptive name indicating event-driven architecture
  
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

### Custom Health Properties
- `producer.health.timeout.ms=1000` (1s, default if not set)
  - **Purpose:** Timeout for Kafka health checks
  - **Location:** `KafkaHealthService.java`
- `producer.health.cache.ttl.ms=2000` (2s, default if not set)
  - **Purpose:** Cache health check results to avoid spamming Kafka
  - **Location:** `KafkaHealthService.java`

### Health Endpoints
- **GET `/cart-service/health/live`**
  - Always returns 200 OK (checks service only)
  - No Kafka dependency
- **GET `/cart-service/health/ready`**
  - Returns 200 OK if Kafka reachable + topic exists
  - Returns 503 Service Unavailable if Kafka down
  - Checks both service and Kafka

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
   - If fails: retry up to 12 times with exponential backoff
     - Delays: 100ms → 200ms → 400ms → 800ms → 1.6s → 3.2s → 6.4s → 12.8s → 25.6s → ...
   - Idempotence prevents duplicates
   - Max 120s total delivery timeout
3. **Application blocks** on `.get(10000)` waiting for result
4. **On success:** Return 201/200 to client
5. **On failure:** Throw `ProducerSendException` → 500 error

**No manual retry loops** - all handled by Kafka client internally with exponential backoff.

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
| **Ordering** | Idempotence enabled, key-based partitioning |
| **Retries** | `retries=12`, exponential backoff (100ms → 25.6s) |
| **Latency** | `linger.ms=5` (batch for throughput) |
| **Throughput** | `batch.size=16384`, `compression.type=snappy` |
| **Timeouts** | `request=5s`, `delivery=120s`, `send=10s` |
| **Health** | Cache 2s, timeout 1s |
