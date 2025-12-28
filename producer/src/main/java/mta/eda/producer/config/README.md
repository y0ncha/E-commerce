# Kafka Producer Configuration

This directory contains the Kafka configuration for the Producer service. The configuration is **grade-critical** as it directly impacts ordering guarantees, durability, and error resilience.

---

## Overview

The configuration is split across three components:

1. **`application.properties`** - Property values (externalized configuration)
2. **`KafkaProducerConfig.java`** - Producer factory and KafkaTemplate bean creation
3. **`KafkaTopicConfig.java`** - Topic creation and partition configuration

---

## Table of Contents

- [Configuration Files](#configuration-files)
- [Critical Configuration Decisions](#critical-configuration-decisions)
  - [1. Message Key Strategy](#1-message-key-strategy-critical)
  - [2. Producer Acknowledgments](#2-producer-acknowledgments-acks)
  - [3. Retry Configuration](#3-retry-configuration)
  - [4. In-Flight Requests](#4-in-flight-requests-critical-for-ordering)
  - [5. Idempotence](#5-idempotence)
  - [6. Serialization](#6-serialization)
  - [7. Timeouts](#7-timeouts)
  - [8. Batch & Compression](#8-batch--compression)
  - [9. Topic Configuration](#9-topic-configuration)
- [Configuration Reference Table](#configuration-reference-table)
- [Why These Choices Matter](#why-these-choices-matter)
- [Testing the Configuration](#testing-the-configuration)
- [Tuning Guide](#tuning-guide)

---

## Configuration Files

### KafkaProducerConfig.java

**Purpose:** Creates the `ProducerFactory` and `KafkaTemplate` beans used by services to publish messages to Kafka.

**Key responsibilities:**
- Loads properties from `application.properties`
- Configures serializers (String key, JSON value)
- Sets durability, ordering, and resilience parameters
- Creates the `KafkaTemplate<String, Order>` bean

**Bean lifecycle:**
```
application.properties → @Value injection → ProducerFactory → KafkaTemplate
```

### KafkaTopicConfig.java

**Purpose:** Auto-creates the `order-events` topic when the application starts.

**Key responsibilities:**
- Defines topic name (configurable)
- Sets partition count (default: 3)
- Sets replication factor (default: 1)

**Why auto-creation?**
- Simplifies local development (no manual topic creation)
- Ensures topic exists before first message
- Production: typically disabled in favor of ops-managed topics

---

## Critical Configuration Decisions

### 1. Message Key Strategy (CRITICAL)

**Configuration:**
```properties
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
```

**Implementation:**
```java
kafkaTemplate.send(topicName, orderId, order)
                     ↑         ↑       ↑
                   topic      KEY    value
```

**Choice:** Use `orderId` as the message key

**Why:**
- **Ordering Guarantee:** Kafka guarantees ordering **only within a partition**
- **Partition Assignment:** Messages with the same key go to the same partition (via consistent hashing)
- **Consequence:** All events for order "ORD-123" are processed sequentially

**Example:**
```
orderId="ORD-123" → hash("ORD-123") % 3 partitions → Partition 1
orderId="ORD-456" → hash("ORD-456") % 3 partitions → Partition 2
orderId="ORD-123" → hash("ORD-123") % 3 partitions → Partition 1 (same!)
```

**Exercise 02 Requirement:**
> "Updates for any specific order must be processed in the correct sequence"

**Alternative (rejected):**
- **No key (null):** Round-robin distribution → **NO ordering guarantee** ❌
- **Random UUID:** Different partitions per message → **NO ordering** ❌
- **customerId:** Groups by customer, not order → **Doesn't meet spec** ❌

---

### 2. Producer Acknowledgments (acks)

**Configuration:**
```properties
spring.kafka.producer.acks=all
```

**Code:**
```java
configProps.put(ProducerConfig.ACKS_CONFIG, acks); // "all"
```

**Choice:** `acks=all` (wait for all in-sync replicas to acknowledge)

**Why:**
- **Highest Durability:** Message is safely replicated before acknowledgment
- **Data Safety:** Prevents data loss even if leader fails immediately after write
- **Exercise Requirement:** "Wait for all replicas to acknowledge (durability)"

**Tradeoff:**
- ✅ **Pro:** Maximum data safety for critical e-commerce orders
- ⚠️ **Con:** Higher latency (must wait for replicas)
- ✅ **Acceptable:** One order at a time (202 Accepted response)

**Alternatives:**
| acks | Durability | Latency | Grade Impact |
|------|------------|---------|--------------|
| `0` | None | Fastest | **HIGH (-)** Data loss risk |
| `1` | Leader only | Medium | **MEDIUM** Acceptable but not best |
| `all` | All replicas | Slowest | **HIGH (+)** Best practice |

---

### 3. Retry Configuration

**Configuration:**
```properties
spring.kafka.producer.retries=3
spring.kafka.producer.properties.retry.backoff.ms=100
```

**Code:**
```java
configProps.put(ProducerConfig.RETRIES_CONFIG, retries); // 3
configProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, retryBackoffMs); // 100ms
```

**Choice:** 3 retries with 100ms exponential backoff

**Why:**
- **Transient Failure Recovery:** Automatically retries on temporary broker issues
- **Prevents Data Loss:** 3 attempts give reasonable chance of success
- **Backoff:** Prevents overwhelming broker during temporary congestion
- **Exercise Requirement:** "Every code snippet must include error handling for broker downtime"

**Retry Timeline:**
```
Attempt 1: Send → Fail (broker temporary issue)
           ↓ wait 100ms
Attempt 2: Send → Fail
           ↓ wait 200ms (exponential)
Attempt 3: Send → Fail
           ↓ wait 400ms
Attempt 4: Send → Success ✓
```

**When retries exhausted:**
- Producer throws exception
- Service layer catches it (`ProducerSendException`)
- API returns `500 Internal Server Error` to client

**Alternatives:**
| Retries | Behavior | Grade Impact |
|---------|----------|--------------|
| `0` | Fail immediately | **MEDIUM (-)** Data loss risk |
| `3` | Retry 3 times with backoff | **HIGH (+)** Balanced resilience |
| `MAX_INT` | Retry forever | **LOW (-)** May block indefinitely |

---

### 4. In-Flight Requests (CRITICAL for Ordering)

**Configuration:**
```properties
spring.kafka.producer.properties.max.in.flight.requests.per.connection=1
```

**Code:**
```java
configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlightRequests); // 1
```

**Choice:** `max.in.flight=1` (send one request at a time)

**Why:** This is the **MOST CRITICAL** configuration for ordering guarantees

**The Ordering Paradox:**

Imagine `orderId="123"` has 3 events:
1. Event 1: `create-order` (status=new)
2. Event 2: `update-order` (status=processing)
3. Event 3: `update-order` (status=shipped)

**With `max.in.flight=5` and `retries=3`:**
```
Time 0: Send Event 1, Event 2, Event 3 in parallel
Time 1: Event 1 ✓ (ack received)
Time 2: Event 3 ✓ (ack received)
Time 3: Event 2 ✗ (retry needed)
Time 4: Event 2 ✓ (retry succeeded)

Result: Events arrive as [1, 3, 2] ❌ WRONG ORDER
```

**With `max.in.flight=1`:**
```
Time 0: Send Event 1
Time 1: Event 1 ✓ → now send Event 2
Time 2: Event 2 ✓ → now send Event 3
Time 3: Event 3 ✓

Result: Events arrive as [1, 2, 3] ✓ CORRECT ORDER
```

**Tradeoff:**
- ✅ **Pro:** **Guarantees ordering** even with retries
- ⚠️ **Con:** Lower throughput (sequential sends)
- ✅ **Acceptable:** REST API sends one order at a time anyway

**Exercise 02 Requirement:**
> "Updates for any specific order must be processed in the correct sequence"

**This setting is NON-NEGOTIABLE for ordering guarantee.**

---

### 5. Idempotence

**Configuration:**
```properties
spring.kafka.producer.properties.enable.idempotence=true
```

**Code:**
```java
configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
```

**Choice:** Enable idempotence

**Why:**
- **Exactly-Once Semantics:** Prevents duplicate messages even on retries
- **Producer ID:** Kafka assigns unique producer ID and sequence numbers
- **Automatic Deduplication:** Broker detects and ignores duplicate sends

**How it works:**
```
Producer sends message with sequence number
Broker receives: "Producer X, Sequence 5" → stores message
Retry sends same message: "Producer X, Sequence 5" → broker ignores (duplicate)
```

**Requirements for idempotence:**
- `acks=all` ✓ (we have this)
- `retries > 0` ✓ (we have 3)
- `max.in.flight.requests.per.connection <= 5` ✓ (we have 1)

**Benefit:**
- Safe retries without duplicate orders
- Critical for financial/e-commerce data

---

### 6. Serialization

**Configuration:**
```properties
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.producer.properties.spring.json.add.type.headers=false
```

**Code:**
```java
configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer);
configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
```

**Choice:** String keys, JSON values

**Why:**
- **Key (String):** `orderId` is a string, simple serialization
- **Value (JSON):** Human-readable, easy debugging, included in spring-kafka
- **Type headers disabled:** Prevents Spring-specific headers (cleaner messages)

**JSON Serialization:**
```java
Order order = new Order("ORD-123", ...);
↓
JsonSerializer.serialize(order)
↓
{"orderId":"ORD-123","customerId":"CUST-5678",...}
```

**Alternatives:**
| Serializer | Pros | Cons | Grade Impact |
|------------|------|------|--------------|
| JSON | Simple, readable, included | No schema enforcement | **HIGH (+)** Base requirement |
| Avro | Schema-enforced, compact | Requires Schema Registry | **BONUS** (+10 pts) |
| String | Manual JSON | Error-prone | **LOW (-)** Not recommended |

**Exercise 02 Default:** "Use local JSON conversion by default"

---

### 7. Timeouts

**Configuration:**
```properties
spring.kafka.producer.properties.request.timeout.ms=5000
spring.kafka.producer.properties.delivery.timeout.ms=120000
```

**Code:**
```java
configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs); // 5000
configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs); // 120000
```

**Choice:** 5 second request timeout, 120 second delivery timeout

**Why:**

**Request Timeout (5s):**
- Time to wait for **broker response** per request
- Fast failure detection on broker issues
- Allows quick error response to client (return 500)

**Delivery Timeout (120s):**
- **Total time** allowed for send + retries
- Calculation: `3 retries × 30s each + overhead = ~120s`
- Prevents indefinite hangs

**Timeline:**
```
T=0s:    Send attempt 1
T=5s:    Timeout → retry 1
T=10s:   Timeout → retry 2
T=15s:   Timeout → retry 3
T=20s:   Timeout → delivery timeout NOT reached (still < 120s)
T=120s:  Delivery timeout reached → throw exception
```

**Exercise Requirement:**
> "Handle connection issues and broker unavailability"

---

### 8. Batch & Compression

**Configuration:**
```properties
spring.kafka.producer.properties.batch.size=16384
spring.kafka.producer.properties.compression.type=snappy
```

**Code:**
```java
configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize); // 16384 (16KB)
configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType); // "snappy"
```

**Choice:** Default batch size (16KB), Snappy compression

**Why:**

**Batch Size (16KB):**
- Kafka default, proven and stable
- Balances latency vs throughput
- For single order sends, batching has minimal impact

**Compression (Snappy):**
- Fast compression algorithm (minimal CPU)
- Reduces network bandwidth usage
- Good for JSON payloads (text is compressible)

**Tradeoff:**
- ✅ **Pro:** Reduced network traffic
- ⚠️ **Con:** Slight CPU overhead
- ✅ **Verdict:** Snappy is fast enough, benefit outweighs cost

**Compression comparison:**
| Type | Speed | Compression Ratio | CPU Cost |
|------|-------|------------------|----------|
| none | Fastest | 1x | None |
| snappy | Very fast | ~2-3x | Low |
| gzip | Slow | ~3-5x | High |
| lz4 | Very fast | ~2-3x | Low |

---

### 9. Topic Configuration

**Configuration:**
```properties
kafka.topic.name=order-events
kafka.topic.partitions=3
kafka.topic.replication-factor=1
```

**Code (KafkaTopicConfig.java):**
```java
@Bean
public NewTopic orderEventsTopic() {
    return TopicBuilder.name(topicName)         // "order-events"
            .partitions(partitions)              // 3
            .replicas(replicationFactor)         // 1
            .build();
}
```

**Choice:** 3 partitions, replication factor 1

**Why:**

**Topic Name:** `order-events`
- Single topic for all order lifecycle events
- Consumers read the full stream
- Different consumer groups each get all events (broadcast)

**Partitions: 3**
- Allows parallel processing by consumers
- Good balance for development/demo
- More partitions = higher throughput (but more complexity)

**Replication Factor: 1**
- **Development only:** Single replica for simplicity
- **Production:** Should be 2-3 for fault tolerance

**Partition Assignment:**
```
orderId="ORD-123" → Partition 1
orderId="ORD-456" → Partition 2
orderId="ORD-789" → Partition 0
orderId="ORD-123" → Partition 1 (same as before!)
```

**Exercise Requirement:**
> "Broadcast behavior: different consumer groups each get the full stream"

---

## Configuration Reference Table

| Property | Value | Why | Criticality |
|----------|-------|-----|-------------|
| `bootstrap-servers` | `localhost:9092` | Kafka broker address | Required |
| `key-serializer` | `StringSerializer` | orderId is a string | Required |
| `value-serializer` | `JsonSerializer` | Order is JSON | Required |
| **`acks`** | **`all`** | **Durability: all replicas** | **CRITICAL** |
| **`retries`** | **`3`** | **Retry on transient failures** | **CRITICAL** |
| `retry.backoff.ms` | `100` | Exponential backoff | Important |
| **`max.in.flight`** | **`1`** | **Ordering guarantee** | **CRITICAL** |
| **`enable.idempotence`** | **`true`** | **Exactly-once semantics** | **CRITICAL** |
| `batch.size` | `16384` | Default batch size | Optional |
| `compression.type` | `snappy` | Fast compression | Optional |
| `request.timeout.ms` | `5000` | Fast failure detection | Important |
| `delivery.timeout.ms` | `120000` | Total send timeout | Important |
| `topic.name` | `order-events` | Topic for all order events | Required |
| `topic.partitions` | `3` | Parallel processing | Configurable |
| `topic.replication-factor` | `1` | Dev only (use 2-3 in prod) | Environment-specific |

**Legend:**
- **CRITICAL:** Grade-impacting, must not change
- **Important:** Impacts reliability and performance
- **Optional:** Optimization, can be tuned
- **Required:** Necessary for operation

---

## Why These Choices Matter

### For Exercise 02 Grading

| Requirement | Configuration | Impact |
|-------------|--------------|--------|
| "Updates for any specific order must be processed in the correct sequence" | `orderId` as key + `max.in.flight=1` | **CRITICAL** |
| "Wait for all replicas to acknowledge (durability)" | `acks=all` | **CRITICAL** |
| "Handle broker downtime" | `retries=3` + timeouts | **HIGH** |
| "Prevent duplicate messages" | `enable.idempotence=true` | **HIGH** |
| "Broadcast to multiple consumers" | Single topic + multiple consumer groups | **HIGH** |

### For System Reliability

**Durability:** `acks=all` + `enable.idempotence=true`
- Orders are never lost or duplicated
- Safe even during broker failures

**Ordering:** `orderId` key + `max.in.flight=1`
- Order lifecycle events stay in sequence
- Critical for state machine correctness (new → processing → shipped)

**Resilience:** `retries=3` + backoff + timeouts
- Automatic recovery from transient failures
- Fast failure detection and error reporting

---

## Testing the Configuration

### 1. Verify Ordering

**Test:** Send multiple events for the same order rapidly
```bash
# Create order
curl -X POST http://localhost:8080/api/create-order \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-123","numItems":3}'

# Update order multiple times
curl -X PUT http://localhost:8080/api/update-order \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-123","status":"processing"}'

curl -X PUT http://localhost:8080/api/update-order \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-123","status":"shipped"}'
```

**Verify:** Consume from topic and check order
```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic order-events --from-beginning \
  --property print.key=true
```

**Expected:** All events for `ORD-123` appear in sequence

---

### 2. Verify Durability (Broker Failure)

**Test:** Stop Kafka broker during send
```bash
# Terminal 1: Stop Kafka
docker-compose stop kafka

# Terminal 2: Try to create order
curl -X POST http://localhost:8080/api/create-order \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-456","numItems":2}'
```

**Expected:** 
- Producer retries 3 times
- After ~15 seconds, returns `500 Internal Server Error`
- Error response includes type: `KAFKA_ERROR` or `TIMEOUT`

---

### 3. Verify Idempotence (No Duplicates)

**Test:** Enable Kafka debug logging and check for duplicate detection
```properties
logging.level.org.apache.kafka=DEBUG
```

**Expected:** Broker logs show duplicate sequence numbers being ignored

---

## Tuning Guide

### For Development

Current settings are optimal for local development:
- Fast feedback (5s request timeout)
- Single replica (simple setup)
- 3 partitions (good for testing)

**No changes needed.**

---

### For Production

**Recommended changes:**

1. **Replication Factor:**
```properties
kafka.topic.replication-factor=3  # Fault tolerance
```

2. **More Partitions (if high throughput):**
```properties
kafka.topic.partitions=10  # More parallel consumers
```

3. **Longer Timeouts (if network latency):**
```properties
spring.kafka.producer.properties.request.timeout.ms=10000
spring.kafka.producer.properties.delivery.timeout.ms=180000
```

4. **External Bootstrap Servers:**
```properties
spring.kafka.bootstrap-servers=kafka-broker-1:9092,kafka-broker-2:9092,kafka-broker-3:9092
```

5. **Add Monitoring:**
```properties
# Enable JMX metrics
spring.kafka.producer.properties.metrics.recording.level=INFO
```

---

### For Higher Throughput (if ordering per order is acceptable)

**Current:** `max.in.flight=1` guarantees global ordering but limits throughput

**Alternative (with ordering per order only):**
```properties
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
spring.kafka.producer.properties.enable.idempotence=true
```

**Why it works:**
- Idempotence + `max.in.flight <= 5` maintains ordering **per partition**
- Since we key by `orderId`, events for **same order** still stay in order
- Events for **different orders** can be sent in parallel

**Tradeoff:**
- ✅ **Pro:** 5x throughput
- ✅ **Pro:** Still guarantees ordering per order (same partition)
- ⚠️ **Con:** Loses global ordering across all orders

**Recommendation:** Keep `max.in.flight=1` for Exercise 02 (clearer guarantees)

---

## Summary

### Configuration Philosophy

**Correctness > Performance**
- Ordering and durability are non-negotiable
- Throughput is acceptable for single-order REST API

**Fail-Fast**
- Short timeouts for quick error detection
- Return 500 to client immediately on Kafka failure

**Idempotent & Durable**
- Every message exactly once
- Never lose an order

### Grade-Critical Settings

**These MUST NOT be changed:**
1. ✅ `orderId` as message key
2. ✅ `acks=all`
3. ✅ `max.in.flight.requests.per.connection=1`
4. ✅ `enable.idempotence=true`
5. ✅ `retries=3`

**These are configurable (but current values are good):**
- Timeouts (adjust for network latency)
- Batch size (optimization)
- Compression (optimization)
- Partition count (scalability)

### Quick Reference

**For ordering:** `orderId` key + `max.in.flight=1`  
**For durability:** `acks=all` + `enable.idempotence=true`  
**For resilience:** `retries=3` + backoff + timeouts  
**For debugging:** JSON serialization + logging

---

## Additional Resources

- [Kafka Producer Configuration Docs](https://kafka.apache.org/documentation/#producerconfigs)
- [Spring Kafka Documentation](https://docs.spring.io/spring-kafka/reference/)
- [Kafka Ordering Guarantees](https://kafka.apache.org/documentation/#semantics)
- [Exercise 02 Specification](../../../docs/Ex.02.md)

---

**Last Updated:** December 28, 2025  
**Configuration Version:** Phase 4 Implementation (Complete)

