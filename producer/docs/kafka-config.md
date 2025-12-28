# Kafka Producer Configuration Plan

**Project:** Kafka E-commerce Backend (Exercise 2)  
**Focus:** Critical producer-side configuration decisions  
**Date:** December 28, 2025

---

## Overview

Kafka producer configuration is grade-critical because it directly impacts:
- **Ordering guarantees** (Ex.02 requirement)
- **Data durability** (architectural safety)
- **Error resilience** (grading criterion per AGENTS.md)

This document analyzes all major configuration options with pros/cons.

---

## 1. Message Key Strategy (CRITICAL)

| Option | Key Choice | Pros | Cons | Grade Impact |
|--------|-----------|------|------|-------------|
| **A: orderId as key** | `kafkaTemplate.send(topic, orderId, order)` | ✅ Guarantees same partition → ordering ✅ Meets Ex.02 requirement ✅ Prevents race conditions | None | **HIGH (+)** |
| **B: No key (null)** | `kafkaTemplate.send(topic, order)` | Round-robin distribution across partitions | ❌ No ordering guarantee ❌ Violates Ex.02 spec ❌ Updates may arrive out-of-order | **HIGH (-)** |
| **C: Random UUID** | `kafkaTemplate.send(topic, UUID.random(), order)` | Fair load distribution | ❌ Different orders in different partitions ❌ Still violates ordering requirement | **HIGH (-)** |
| **D: customerId as key** | `kafkaTemplate.send(topic, customerId, order)` | Groups by customer | ❌ Doesn't guarantee order-level ordering ❌ Doesn't meet spec | **HIGH (-)** |

**Recommendation: A (orderId as key)** ← **This is mandatory per Ex.02**

**Rationale:**
- Ex.02 states: "updates for any specific order must be processed in the correct sequence"
- Only way to guarantee this: same partition per order
- Only way to guarantee same partition: consistent hash key (orderId)

---

## 2. Producer Acknowledgments (acks)

| Option | Config | Semantics | Durability | Latency | Grade Impact |
|--------|--------|-----------|-----------|---------|-------------|
| **A: acks=0** | `acks=0` | Fire-and-forget | ❌ Lowest | Fastest | **MEDIUM (-)** Data loss risk |
| **B: acks=1** | `acks=1` | Leader acknowledged | ⚠️ Moderate | Medium | **MEDIUM (-/+)** Acceptable but not best |
| **C: acks=all** | `acks=all` | All replicas acknowledged | ✅ Highest | Slowest | **HIGH (+)** Best durability |

**Kafka Default:** `acks=1`  
**AGENTS.md Requirement:** "Wait for all replicas to acknowledge (durability)"

**Recommendation: C (acks=all)**

**Rationale:**
- AGENTS.md explicitly states: "Wait for all replicas to acknowledge (durability)"
- E-commerce orders = critical data (financial impact)
- Slight latency cost worth guaranteed durability
- One order at a time is acceptable (202 Accepted response)

---

## 3. Retry Configuration

| Option | Config | Behavior | Pros | Cons | Grade Impact |
|--------|--------|----------|------|------|-------------|
| **A: No retries** | `retries=0` | Fail immediately | Fast failure | ❌ Data loss on broker failure | **MEDIUM (-)** |
| **B: 3 retries** | `retries=3` | Retry up to 3x with backoff | ✅ Resilient ✅ Per AGENTS.md | Slightly slower | **HIGH (+)** |
| **C: Infinite retries** | `retries=MAX_INT` | Keep retrying forever | Most resilient | ❌ May block indefinitely | **LOW (-)** |
| **D: With backoff** | `retries=3` + `retry.backoff.ms=100` | Exponential backoff between retries | ✅ Prevents overwhelming broker | Additional complexity | **HIGH (+)** |

**Recommendation: B (retries=3) with backoff**

**Rationale:**
- AGENTS.md: "Every code snippet must include error handling for broker downtime"
- 3 retries = reasonable balance (not too aggressive, not too lenient)
- Backoff prevents hammering broker during temporary issues
- 100ms backoff = ~100ms total wait per failure

---

## 4. In-Flight Requests (CRITICAL for Ordering)

| Option | Config | Impact | Ordering | Throughput | Grade Impact |
|--------|--------|--------|----------|-----------|-------------|
| **A: max=1** | `max.in.flight.requests.per.connection=1` | Sequential sends | ✅ **Guarantees order** | Lower | **HIGH (+)** |
| **B: max=5** | `max.in.flight.requests.per.connection=5` | Pipeline 5 requests | ⚠️ May reorder on retry | Higher | **MEDIUM (-)** |
| **C: max=unlimited** | (Default, can be large) | Maximum parallelism | ❌ **Can lose ordering** | Highest | **HIGH (-)** |

**Why This Matters (Ordering Paradox):**

Scenario: orderId="123" has 3 events
- Event 1: create-order (status=new)
- Event 2: update-order (status=processing)
- Event 3: update-order (status=shipped)

**With max=5 and retries=3:**
1. All 3 events sent in parallel → partition assignments may vary
2. Event 2 retries → but Event 3 already succeeded
3. **Result:** Events arrive as [1, 3, 2] ❌ WRONG ORDER

**With max=1:**
1. Event 1 sent, waits for ack
2. Event 2 sent only after Event 1 acked
3. Event 3 sent only after Event 2 acked
4. **Result:** Events arrive as [1, 2, 3] ✅ CORRECT ORDER

**Recommendation: A (max=1)**

**Rationale:**
- Ex.02 critical requirement: "correct sequence"
- Only `max=1` guarantees ordering when combined with retries
- Throughput cost acceptable (one order at a time from REST API perspective)
- AGENTS.md lists as "Key Architectural Safeguard"

---

## 5. Batch & Compression Settings

| Option | Config | Latency Impact | Throughput | Grade Impact |
|--------|--------|---|---|---|
| **A: Small batches** | `batch.size=100` | Lower (per-order latency) | Lower throughput | **NEUTRAL** Works fine |
| **B: Medium batches** | `batch.size=16384` (default) | Medium | Good balance | **GOOD** Standard choice |
| **C: Large batches** | `batch.size=32768` | Higher | Higher throughput | **ACCEPTABLE** But adds latency |
| **Compression: none** | `compression.type=none` | Fastest | More network traffic | **ACCEPTABLE** JSON is already compressible |
| **Compression: snappy** | `compression.type=snappy` | Slight CPU cost | Reduced network | **GOOD** Fast compression |

**Recommendation: Medium batches (16384) + snappy compression**

**Rationale:**
- Default batch size is proven and stable
- Snappy = fast algorithm (minimal CPU cost)
- Reduces network bandwidth usage
- No grading impact either way (optimization detail)

---

## 6. Timeout & Delivery Configuration

| Option | Config | Behavior | Grade Impact |
|--------|--------|----------|-------------|
| **A: request.timeout.ms=30000** | Default (30s) | Reasonable timeout | **GOOD** |
| **B: request.timeout.ms=5000** | 5s timeout | Fail faster on broker issues | **BETTER** For quick error response |
| **C: delivery.timeout.ms=120000** | Total send timeout | Limits total retry time | **GOOD** Prevents indefinite hangs |

**Recommendation: request.timeout.ms=5000 + delivery.timeout.ms=120000**

**Rationale:**
- Ex.02: "Handle connection issues and broker unavailability"
- 5s request timeout = fast failure detection
- 120s delivery timeout = allows for retries (3 retries × 30s each)
- Enables quick error response to client (return 500)

---

## 7. Serialization Strategy

| Option | Serializer | Pros | Cons | Grade Impact |
|--------|-----------|------|------|-------------|
| **A: JsonSerializer** | `org.springframework.kafka.support.serializer.JsonSerializer` | ✅ Simple ✅ Human-readable ✅ JSON already in pom.xml | ❌ Not schema-enforced (may miss Avro bonus) | **HIGH (+)** Base requirement |
| **B: Avro** | Requires Schema Registry | ✅ Schema-enforced ✅ +10 bonus points | Complex setup, schema registry needed | **BONUS** |
| **C: String** | Manual JSON string conversion | Simple | Error-prone serialization | **LOW (-)** Not recommended |

**Recommendation: A (JsonSerializer, base) with optional B (Avro) for bonus**

**Rationale:**
- Ex.02: "Use local JSON conversion by default"
- JsonSerializer included with spring-kafka
- Avro is optional bonus (+10 pts) — can add later
- Start with JSON for simplicity, upgrade to Avro if time permits

---

## Final Recommended Configuration

### application.properties

```properties
# Server Configuration
server.port=8080

# Kafka Bootstrap
spring.kafka.bootstrap-servers=localhost:9092

# Key Serializer (orderId as message key)
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer

# Value Serializer (JSON format)
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer

# Producer Acknowledgments (Durability - Critical)
spring.kafka.producer.acks=all

# Retry Configuration (Resilience - Critical)
spring.kafka.producer.retries=3
spring.kafka.producer.properties.retry.backoff.ms=100

# In-Flight Requests (Ordering - CRITICAL)
spring.kafka.producer.properties.max.in.flight.requests.per.connection=1

# Batch & Compression (Performance)
spring.kafka.producer.properties.batch.size=16384
spring.kafka.producer.properties.compression.type=snappy

# Timeout (Connection Handling - Per Ex.02)
spring.kafka.producer.properties.request.timeout.ms=5000
spring.kafka.producer.properties.delivery.timeout.ms=120000

# Topic Name
kafka.topic.name=order-events

# Logging
logging.level.root=INFO
logging.level.mta.eda.producer=DEBUG
```

---

## Configuration in Code (KafkaProducerConfig.java)

Key implementation points:

```java
@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public ProducerFactory<String, Order> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Ordering guarantee (CRITICAL)
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Durability (Critical)
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        
        // Resilience (Critical)
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        
        // Ordering on retries (CRITICAL)
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        
        // Timeout (Critical)
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        
        // Performance (Optional)
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        return new DefaultProducerFactory<>(configProps);
    }
    
    @Bean
    public KafkaTemplate<String, Order> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

---

## Grade-Critical Decisions Summary

| Decision | Choice | Why | Grade Risk |
|----------|--------|-----|-----------|
| **Message Key** | orderId | Ordering requirement | **CRITICAL** |
| **Acknowledgments** | acks=all | Durability + AGENTS.md spec | **HIGH** |
| **Max In-Flight** | max=1 | Ordering on retries | **CRITICAL** |
| **Retries** | retries=3 + backoff | Error handling priority | **HIGH** |
| **Timeout** | 5s + 120s | Connection issue handling | **MEDIUM** |
| **Serialization** | JSON (+ optional Avro) | Per Ex.02 spec | **HIGH** |

---

## Implementation Order

1. **Phase 3: Configuration Layer** → Create `KafkaProducerConfig.java` with config above
2. **Phase 4: Service Layer** → Use `KafkaTemplate.send(topic, orderId, order)` with orderId key
3. **Phase 5: Controller** → Handle 202 Accepted responses, 500 on Kafka failure
4. **Phase 6: Error Handling** → Catch `KafkaException`, return proper HTTP status codes
5. **Phase 10: Optional Bonus** → Add Avro serialization if time permits

---

## Key Takeaways

✅ **orderId as message key** = Non-negotiable (Ex.02 requirement)  
✅ **acks=all** = Required (AGENTS.md safeguard)  
✅ **max.in.flight=1** = Required (Ordering guarantee with retries)  
✅ **retries=3** = Required (Error handling per AGENTS.md)  
✅ **JSON serialization** = Default (Avro is bonus)

**Ready to implement Phase 3 when you approve this configuration.**

