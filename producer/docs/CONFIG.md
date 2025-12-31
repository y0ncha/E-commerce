# Configuration Overview

Complete reference for all configuration properties in the Producer (Cart Service).

---

## Application Settings

### Server
- `spring.application.name=producer`
- `server.port=8080`

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
  - **Docker:** Set `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` via environment variable
  - **Purpose:** Initial connection to Kafka cluster

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
- `spring.kafka.producer.properties.linger.ms=0`
  - **Purpose:** Artificial delay to accumulate batches (0 = send immediately)
  - **Trade-off:** Higher values improve throughput, 0 minimizes latency
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
  - **Validation:** `120000 > 5000 * 4 + 0 = 20000` ✅
- `spring.kafka.producer.properties.max.block.ms=5000` (5s)
  - **Purpose:** Max time to block waiting for metadata/buffer space
- `producer.send.timeout.ms=10000` (10s, custom property)
  - **Purpose:** Timeout for `.send().get()` calls in application code
  - **Location:** `KafkaProducerService.java`

---

## Topic Configuration

- `kafka.topic.name=order-events`
  - **Purpose:** Topic name for all order events
- `kafka.topic.partitions=3`
  - **Purpose:** Number of partitions for parallel processing
  - **Recommendation:** >= number of consumer instances for parallelism
- `kafka.topic.replication-factor=3`
  - **Purpose:** Number of replicas per partition (high availability)
  - **Current Setup:** 3 (production-grade fault tolerance)
  - **Benefits:**
    - ✅ Tolerates 2 broker failures simultaneously
    - ✅ Zero data loss with `acks=all` (all in-sync replicas must acknowledge)
    - ✅ Leader election ensures availability
  - **Trade-off:**
    - ❌ Higher disk usage (3x storage)
    - ❌ Slightly higher latency (wait for 3 replicas)
  - **With `acks=all` + `replication-factor=3`:** True durability - data survives broker failures

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
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
# Uses: kafka:9092 (container network)
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

## Quick Reference

| Property Category | Key Settings |
|-------------------|--------------|
| **Durability** | `acks=all`, `enable.idempotence=true` |
| **Ordering** | Idempotence enabled, key-based partitioning |
| **Retries** | `retries=12`, exponential backoff (100ms → 25.6s) |
| **Latency** | `linger.ms=0` (send immediately) |
| **Throughput** | `batch.size=16384`, `compression.type=snappy` |
| **Timeouts** | `request=5s`, `delivery=120s`, `send=10s` |
| **Health** | Cache 2s, timeout 1s |


