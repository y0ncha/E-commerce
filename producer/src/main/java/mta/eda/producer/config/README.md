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
```text
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
```text
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
```text
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
```text
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
spring.kafka.producer.retries=12
spring.kafka.producer.properties.retry.backoff.ms=100
```

**Choice:** 12 retries with 100ms exponential backoff

**Why:**
- **Transient Failure Recovery:** Automatically retries on temporary broker issues
- **Prevents Data Loss:** 12 attempts cover most network partition recovery windows (~60s)
- **Backoff:** Prevents overwhelming broker during temporary congestion
- **Exercise Requirement:** "Every code snippet must include error handling for broker downtime"

**Retry Timeline:**
```text
Attempt 1: Send → Fail (broker temporary issue)
           ↓ wait 100ms
Attempt 2: Send → Fail
           ↓ wait 200ms (exponential)
Attempt 3: Send → Fail
           ↓ wait 400ms
...
Attempt 12: Success ✓
```

**When retries exhausted:**
- Producer throws exception
- Service layer catches it (`ProducerSendException`)
- API returns `500 Internal Server Error` to client

---

### 4. In-Flight Requests (CRITICAL for Ordering)

**Configuration:**
```properties
spring.kafka.producer.properties.max.in.flight.requests.per.connection=1
```

**Choice:** `max.in.flight=1` (send one request at a time)

**Why:** This is the **MOST CRITICAL** configuration for ordering guarantees

**The Ordering Paradox:**

Imagine `orderId="123"` has 3 events:
1. Event 1: `create-order` (status=new)
2. Event 2: `update-order` (status=processing)
3. Event 3: `update-order` (status=shipped)

**With `max.in.flight=5` and `retries=3`:**
```text
Time 0: Send Event 1, Event 2, Event 3 in parallel
Time 1: Event 1 ✓ (ack received)
Time 2: Event 3 ✓ (ack received)
Time 3: Event 2 ✗ (retry needed)
Time 4: Event 2 ✓ (retry succeeded)

Result: Events arrive as [1, 3, 2] ❌ WRONG ORDER
```

**With `max.in.flight=1`:**
```text
Time 0: Send Event 1
Time 1: Event 1 ✓ → now send Event 2
Time 2: Event 2 ✓ → now send Event 3
Time 3: Event 3 ✓

Result: Events arrive as [1, 2, 3] ✓ CORRECT ORDER
```

**Exercise 02 Requirement:**
> "Updates for any specific order must be processed in the correct sequence"

---

### 5. Idempotence

**Configuration:**
```properties
spring.kafka.producer.properties.enable.idempotence=true
```

**Choice:** Enable idempotence

**Why:**
- **Exactly-Once Semantics:** Prevents duplicate messages even on retries
- **Producer ID:** Kafka assigns unique producer ID and sequence numbers
- **Automatic Deduplication:** Broker detects and ignores duplicate sends

**Benefit:**
- Safe retries without duplicate orders
- Critical for financial/e-commerce data

---

### 6. Serialization

**Configuration:**
```properties
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
```

**Choice:** String keys, JSON values

**Why:**
- **Key (String):** `orderId` as String (e.g., "ORD-1A2B")
- **Value (JSON):** Order object serialized as JSON

---

### 7. Timeouts

**Configuration:**
```properties
spring.kafka.producer.properties.request.timeout.ms=5000
spring.kafka.producer.properties.delivery.timeout.ms=120000
```

**Choice:** 5 second request timeout, 120 second delivery timeout

**Why:**
- **Request Timeout (5s):** Fast failure detection on broker issues
- **Delivery Timeout (120s):** Total time allowed for send + retries. Must be > `request.timeout.ms * (retries + 1) + linger.ms`

---

### 8. Batch & Compression

**Configuration:**
```properties
spring.kafka.producer.properties.batch.size=16384
spring.kafka.producer.properties.compression.type=snappy
spring.kafka.producer.properties.linger.ms=5
```

**Choice:** 16KB batch size, Snappy compression, 5ms linger

**Why:**
- **Batch Size (16KB):** Balances latency vs throughput
- **Compression (Snappy):** Reduces network bandwidth usage with minimal CPU
- **Linger (5ms):** Artificial delay to accumulate batches, improving throughput for high-volume traffic

---

### 9. Topic Configuration

**Configuration:**
```properties
kafka.topic.name=order-events
kafka.topic.partitions=3
kafka.topic.replication-factor=1
```

**Choice:** 3 partitions, replication factor 1

**Why:**
- **Partitions: 3:** Allows parallel processing by consumers
- **Replication Factor: 1:** Current setup for single-broker development environment. (Production should use 3)

---

## Configuration Reference Table

| Property | Value | Why | Criticality |
|----------|-------|-----|-------------|
| `bootstrap-servers` | `localhost:9092` | Kafka broker address | Required |
| `key-serializer` | `StringSerializer` | orderId is a string | Required |
| `value-serializer` | `JsonSerializer` | Order is JSON | Required |
| **`acks`** | **`all`** | **Durability: all replicas** | **CRITICAL** |
| **`retries`** | **`12`** | **Retry on transient failures** | **CRITICAL** |
| `retry.backoff.ms` | `100` | Exponential backoff | Important |
| **`max.in.flight`** | **`1`** | **Ordering guarantee** | **CRITICAL** |
| **`enable.idempotence`** | **`true`** | **Exactly-once semantics** | **CRITICAL** |
| `batch.size` | `16384` | Default batch size | Optional |
| `compression.type` | `snappy` | Fast compression | Optional |
| `linger.ms` | `5` | Batch for throughput | Important |
| `request.timeout.ms` | `5000` | Fast failure detection | Important |
| `delivery.timeout.ms` | `120000` | Total send timeout | Important |
| `topic.name` | `order-events` | Topic for all order events | Required |
| `topic.partitions` | `3` | Parallel processing | Configurable |
| `topic.replication-factor` | `1` | Single broker setup | Environment-specific |

---

## Summary

### Grade-Critical Settings

**These MUST NOT be changed:**
1. ✅ `orderId` as message key
2. ✅ `acks=all`
3. ✅ `max.in.flight.requests.per.connection=1`
4. ✅ `enable.idempotence=true`
5. ✅ `retries=12`

---

**Last Updated:** December 28, 2025  
**Configuration Version:** Phase 4 Implementation (Complete)
