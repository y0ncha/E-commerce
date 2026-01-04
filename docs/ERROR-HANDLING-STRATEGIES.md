# Error Handling Documentation for E-commerce Microservices

This document provides a concise overview of the different types of errors handled in the E-commerce microservices system, our handling strategies, and the justification (with pros and cons) for each approach.

---

## Overview

The E-commerce microservices architecture consists of two primary services:
- **Producer (Cart Service)**: Handles order creation and updates, publishes events to Kafka
- **Consumer (Order Service)**: Processes order events from Kafka, calculates shipping costs, maintains order state

Both services implement comprehensive error handling strategies to ensure system reliability, data integrity, and resilience against various failure scenarios.

---

## 1. Business Logic Errors

### Description
Invalid data, rule violations, or attempted operations in unsupported scenarios (e.g., processing an order with insufficient stock, duplicate order creation, invalid status transitions).

### Handling Strategy

**Producer (Cart Service):**
- Custom exceptions thrown in the service layer:
  - `DuplicateOrderException`: Attempting to create an order with existing orderId
  - `OrderNotFoundException`: Updating non-existent order
  - `InvalidStatusTransitionException`: Invalid order status progression
  - `InvalidOrderIdException`: OrderId format validation failures
- `GlobalExceptionHandler` (`@ControllerAdvice`) captures exceptions and returns structured API error responses
- HTTP status codes: 404 (Not Found), 409 (Conflict), 400 (Bad Request)

**Consumer (Order Service):**
- State machine validation enforces strict order status transitions:
  - Progression: NEW → CONFIRMED → DISPATCHED → COMPLETED (strictly sequential)
  - CANCELED can be reached from any non-terminal state
  - Terminal states (COMPLETED, CANCELED) prevent further transitions
- Invalid transitions are logged as warnings but don't halt processing
- Dual-layer idempotency checks prevent duplicate processing:
  - Offset-based: Detects exact message redeliveries from Kafka
  - State-based: Detects duplicate status updates
- `GlobalExceptionHandler` returns structured error responses for API requests

### Justification

**Pros:**
- Provides clients with clear, actionable feedback
- Enforces business rules consistently across the system
- Prevents invalid data from corrupting system state
- State machine validation maintains data integrity even with out-of-order messages
- Idempotency ensures exactly-once semantics at the business logic level

**Cons:**
- Can expose internal details if not mapped carefully
- Adds boilerplate for custom exceptions and error mapping
- State machine validation requires careful design and maintenance
- Idempotency state management adds memory overhead

---

## 2. System/Infrastructure Errors

### Description
Database outages, Kafka broker unavailability, network failures, connection timeouts, topic configuration issues.

### Handling Strategy

**Producer (Cart Service):**

1. **Kafka Producer Client Retry Logic:**
   - Continuous retries within 8-second delivery window (`delivery.timeout.ms=8000`)
   - Per-attempt timeout: 3 seconds (`request.timeout.ms=3000`)
   - Application timeout: 10 seconds (prevents "ghost successes")
   - Exponential backoff: 100ms → 200ms → 400ms → 800ms → 1.6s → 3.2s

2. **Circuit Breaker (Resilience4j):**
   - Trips when 50% of calls fail within sliding window of 10 attempts
   - Opens for 30 seconds (fail-fast mode)
   - Half-open state tests recovery with limited requests
   - Returns HTTP 503 (Service Unavailable) when open

3. **Fallback Mechanisms (Data Safety):**
   - **Primary:** Dead Letter Queue (DLQ) topic `orders-dlq` for failed messages
   - **Secondary:** File-based logging to `failed-orders.log` when Kafka is completely down
   - Rollback logic: Failed creates are removed from state; failed updates restore previous version

4. **Health Monitoring:**
   - Readiness probe (`/health/ready`) checks Kafka connectivity and topic existence
   - Returns HTTP 503 when Kafka is unreachable (load balancer stops routing traffic)
   - Liveness probe (`/health/live`) checks internal service health

**Consumer (Order Service):**

1. **Retry with Exponential Backoff:**
   - `DefaultErrorHandler` with exponential backoff configuration
   - Initial delay: 1 second, multiplier: 2.0, max interval: 10 seconds
   - Max attempts: 3 retries (total ~7 seconds before DLT)
   - Retry sequence: 1s → 2s → 4s

2. **Dead Letter Topic (DLT):**
   - Failed messages sent to `orders-dlq` after exhausting retries
   - Preserves original message with metadata headers (original-topic, error-reason, failed-at)
   - 3 partitions (same as main topic) for potential ordered replay
   - 7-day retention for investigation and remediation

3. **Kafka Connectivity Monitoring:**
   - `KafkaConnectivityService` with Resilience4j exponential backoff
   - Aggressive monitoring: 100ms → 200ms → 400ms → 5s max
   - Infinite retries (never gives up)
   - Adaptive scheduling: 1s when unhealthy, 30s when healthy
   - Auto-start/stop listeners based on connectivity

4. **Health Monitoring:**
   - Readiness probe checks Kafka, service, and local state store
   - Returns HTTP 503 when Kafka is DOWN (consumer cannot function)
   - Supports DEGRADED state (connected but listeners not running)

### Justification

**Pros:**
- Improves resilience against transient faults
- Circuit breaker prevents cascade failures and thread exhaustion
- Avoids overwhelming external systems with retries
- DLQ enables forensic investigation without data loss
- File-based fallback protects against complete Kafka outages
- Health monitoring enables intelligent load balancing
- Auto-recovery reduces operational overhead

**Cons:**
- Retries can amplify problems if not tuned properly
- Circuit breakers require careful threshold configuration
- DLQ requires monitoring, alerting, and cleanup procedures
- Multiple fallback layers increase system complexity
- Exponential backoff may delay recovery detection

---

## 3. Serialization/Deserialization Errors

### Description
Failures to parse/serialize JSON or message payloads, typically in Kafka consumers/producers or REST endpoints. Includes malformed JSON, missing fields, type mismatches, and schema violations.

### Handling Strategy

**Producer (Cart Service):**
- JSON serialization handled by Jackson `ObjectMapper`
- Validation occurs before serialization using Bean Validation annotations
- Serialization failures result in HTTP 500 (Internal Server Error)
- Failed messages logged and sent to DLQ if serialization fails during Kafka send

**Consumer (Order Service):**

1. **Poison Pill Handling:**
   - `JsonProcessingException` caught in `KafkaConsumerService.listen()`
   - Full raw message logged for debugging
   - Message acknowledged immediately to prevent infinite retry loop
   - Consumer continues processing next message

2. **Message Key Validation:**
   - Verifies Kafka message key matches orderId in payload
   - Mismatch logged as warning but processing continues (lenient validation)
   - Ensures data integrity without blocking valid orders

3. **Error Handler Integration:**
   - Native Kafka error handling with `DefaultErrorHandler`
   - Non-deserializable messages sent to DLQ after retries
   - Prevents consumer from blocking on corrupted data

### Justification

**Pros:**
- Prevents poison messages from halting message streams
- Enables forensic investigation via DLQ and detailed logging
- Consumer remains operational despite corrupted messages
- Lenient key validation prevents false positives
- Clear separation between retryable and non-retryable failures

**Cons:**
- DLQs require continuous monitoring and cleanup
- Root causes must be investigated offline
- Messages with serialization errors are lost from normal flow
- Requires robust logging infrastructure for debugging
- May mask data quality issues if not monitored

---

## 4. Validation Errors

### Description
Violations of input constraints, including missing required fields, invalid formats, out-of-range values. Enforced using Bean Validation (`@Valid`, `@NotBlank`, `@Pattern`, etc.).

### Handling Strategy

**Producer (Cart Service):**

1. **Request Validation:**
   - `@Valid` annotation on controller method parameters
   - Bean Validation constraints on DTOs:
     - `@NotBlank`: OrderId must not be empty
     - `@Min(1)`: numItems must be at least 1
     - `@Pattern`: OrderId must be hexadecimal format
   - Automatic validation at controller entry

2. **Error Responses:**
   - `MethodArgumentNotValidException` caught by `GlobalExceptionHandler`
   - Returns HTTP 400 (Bad Request) with field-level error details
   - Structured response includes which field failed and why

3. **Service-Level Validation:**
   - Additional business rule validation in service layer
   - Custom exceptions for complex validation rules
   - Prevents invalid data from reaching Kafka

**Consumer (Order Service):**

1. **Request Validation:**
   - `@NotBlank` on `OrderDetailsRequest.orderId`
   - `@Pattern(regexp = "^[0-9A-Fa-f]+$")` for hexadecimal validation
   - `@NotBlank` on `AllOrdersFromTopicRequest.topicName`

2. **Error Responses:**
   - Validation errors return HTTP 400 with detailed messages
   - Examples: "orderId is required", "orderId must be in hexadecimal format"

3. **Message Validation:**
   - No validation on consumed messages (messages from trusted producer)
   - State machine enforces logical constraints
   - Invalid transitions logged but don't fail processing

### Justification

**Pros:**
- Prevents invalid data from entering the system
- Provides immediate feedback to clients
- Promotes strong contract enforcement
- Reduces downstream errors by catching issues early
- Clear, structured error messages aid debugging
- Automatic validation reduces boilerplate code

**Cons:**
- Can produce verbose error payloads for complex objects
- Clients must be able to interpret validation errors
- Over-validation can reduce flexibility
- Validation logic must be maintained alongside business rules
- May expose internal data structure details

---

## 5. Message Ordering and Duplicate Processing Errors

### Description
Out-of-order or duplicate messages in Kafka topics, late-arriving events, message redeliveries after consumer failures.

### Handling Strategy

**Producer (Cart Service):**

1. **Message Key Strategy:**
   - Uses `orderId` as Kafka message key
   - Ensures all events for same order go to same partition
   - Kafka guarantees FIFO ordering within a partition

2. **State Management:**
   - In-memory `orderStore` maintains current order state
   - Rollback mechanism for failed Kafka sends:
     - Create failures: Order removed from store
     - Update failures: Previous version restored
   - Ensures local state consistency with published events

3. **Idempotency Considerations:**
   - Producer doesn't implement explicit idempotency (not needed)
   - Each create/update is a new event
   - Consumer handles duplicate detection

**Consumer (Order Service):**

1. **Dual-Layer Idempotency:**

   **Layer 1: Offset-Based Detection (KafkaConsumerService)**
   - `ConcurrentHashMap<orderId, ProcessedMessageInfo(offset, timestamp)>`
   - Detects exact message redeliveries: `newOffset <= lastProcessedOffset`
   - Prevents duplicate processing after consumer crashes/restarts
   - Per-orderId tracking with O(1) lookup

   **Layer 2: State-Based Detection (OrderService)**
   - Checks if order already has same status in store
   - Detects duplicate events (same orderId + same status)
   - Works even if messages have different offsets
   - Handles producer-side duplicates or late-arriving messages

2. **State Machine Sequencing:**
   - Enforces strictly sequential transitions: NEW → CONFIRMED → DISPATCHED → COMPLETED
   - Only allows `newOrder = currentOrder + 1` (must progress one step at a time)
   - Exception: CANCELED can be reached from any state
   - Invalid transitions logged as warnings, state preserved
   - Late-arriving events rejected silently

3. **Manual Acknowledgment:**
   - `AckMode.MANUAL_IMMEDIATE` in Kafka listener configuration
   - Messages acknowledged only after successful state update
   - At-Least-Once delivery semantics
   - Guarantees no order events lost due to consumer crashes

### Justification

**Pros:**
- Maintains system correctness and data integrity
- Supports exactly-once semantics at business logic level
- Partitioning by orderId guarantees ordering within partition
- Dual-layer idempotency provides defense in depth
- State machine prevents corruption from out-of-order events
- Manual acknowledgment ensures durability
- Lightweight in-memory tracking with minimal overhead

**Cons:**
- Idempotency state management adds complexity
- In-memory maps consume memory (grows with unique orderIds)
- Strict ordering can reduce throughput (single partition per order)
- State machine requires careful design to accommodate business changes
- Late-arriving messages are permanently rejected (no replay)
- Manual acknowledgment adds failure handling complexity

---

## Error Type Classification

| Error Type | HTTP Status | Handling Layer | Recovery Strategy |
|-----------|-------------|----------------|-------------------|
| **Business Logic Errors** | 400, 404, 409 | Service Layer | Client correction required |
| **Validation Errors** | 400 | Controller Layer | Client correction required |
| **Kafka Unavailable** | 500 | Infrastructure | Retry with backoff + DLQ |
| **Circuit Breaker Open** | 503 | Resilience Layer | Wait for circuit to close |
| **Topic Not Found** | 500 | Infrastructure | Configuration fix required |
| **Serialization Failure** | 500, DLQ | Message Layer | Manual investigation + replay |
| **Out-of-Order Messages** | N/A (logged) | Business Logic | State preserved, event skipped |
| **Duplicate Messages** | N/A (logged) | Idempotency Layer | Automatic deduplication |

---

## Error Response Examples

### Producer Service

**Validation Error (400):**
```json
{
  "timestamp": "2026-01-04T12:00:00.000Z",
  "error": "Bad Request",
  "message": "Validation error",
  "path": "/cart-service/create-order",
  "details": {
    "fieldErrors": {
      "numItems": "must be greater than or equal to 1"
    }
  }
}
```

**Kafka Down (500):**
```json
{
  "timestamp": "2026-01-04T12:00:00.000Z",
  "error": "Internal Server Error",
  "message": "The server encountered an error while publishing the order event.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "KAFKA_DOWN",
    "orderId": "ORD-000A"
  }
}
```

**Circuit Breaker Open (503):**
```json
{
  "timestamp": "2026-01-04T12:00:00.000Z",
  "error": "Service Unavailable",
  "message": "The service is temporarily unavailable due to high failure rates. Please try again later.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "CIRCUIT_BREAKER_OPEN"
  }
}
```

### Consumer Service

**Order Not Found (404):**
```json
{
  "timestamp": "2026-01-04T12:00:00.000Z",
  "error": "Not Found",
  "message": "Order with ID ORD-999 not found in the system.",
  "path": "/order-service/order-details",
  "details": {
    "orderId": "ORD-999"
  }
}
```

**Invalid Request (400):**
```json
{
  "timestamp": "2026-01-04T12:00:00.000Z",
  "error": "Bad Request",
  "message": "Validation error",
  "path": "/order-service/order-details",
  "details": {
    "fieldErrors": {
      "orderId": "orderId must be in hexadecimal format (0-9, A-F)"
    }
  }
}
```

---

## Health Monitoring

### Producer Service Health Endpoints

**Liveness Probe:** `GET /cart-service/health/live`
- Always returns 200 OK if service is running
- Checks internal JVM health only
- Used by container orchestrator to detect dead processes

**Readiness Probe:** `GET /cart-service/health/ready`
- Returns 200 OK when Kafka is accessible
- Returns 503 Service Unavailable when Kafka is down
- Load balancer uses this to route traffic only to healthy instances

### Consumer Service Health Endpoints

**Liveness Probe:** `GET /order-service/health/live`
- Always returns 200 OK if service is running
- Checks internal service responsiveness

**Readiness Probe:** `GET /order-service/health/ready`
- Returns 200 OK when service, Kafka, and local state are healthy
- Supports DEGRADED state (Kafka connected but listeners not running)
- Returns 503 Service Unavailable when Kafka is DOWN
- Consumer cannot function without Kafka

---

## Design Patterns Summary

| Pattern | Implementation | Purpose |
|---------|---------------|---------|
| **Circuit Breaker** | Resilience4j in Producer | Fail-fast during infrastructure failures |
| **Exponential Backoff** | Kafka client + Resilience4j | Progressive retry delays for transient failures |
| **Dead Letter Queue** | Kafka DLQ topic | Preserve failed messages for investigation |
| **Idempotency** | Dual-layer (offset + state) | Prevent duplicate processing |
| **State Machine** | Order status validation | Enforce valid state transitions |
| **Fallback Mechanism** | DLQ + File logging | Ensure no data loss |
| **Health Checks** | Liveness + Readiness | Enable intelligent load balancing |
| **Poison Pill Handling** | Immediate acknowledgment | Prevent consumer blocking |
| **Manual Acknowledgment** | Kafka manual commit | Ensure At-Least-Once delivery |
| **Rollback on Failure** | State restoration | Maintain local consistency |

---

## Best Practices Implemented

1. **Fail Fast for User Requests:** Circuit breaker returns 503 immediately when Kafka is down (no 10s timeout wait)
2. **Fail Safe for Data:** Multiple fallback mechanisms (DLQ, file logging) ensure no order data is lost
3. **Clear Error Communication:** Structured error responses with type, message, and details
4. **Defensive Design:** Poison pill handling prevents consumer from halting on bad messages
5. **Idempotent Processing:** Dual-layer idempotency ensures exactly-once semantics
6. **Observable Systems:** Comprehensive logging at all error points for debugging
7. **Graceful Degradation:** Services continue operating with reduced functionality when dependencies fail
8. **Auto-Recovery:** Background monitoring automatically reconnects when infrastructure recovers

---

## Monitoring and Operations

### Key Metrics to Monitor

**Producer:**
- Circuit breaker state transitions (CLOSED → OPEN → HALF_OPEN)
- Kafka send failures and DLQ message count
- Failed order log file growth rate
- Health check failure rates

**Consumer:**
- DLQ message accumulation (indicates systemic issues)
- Idempotency skip rate (may indicate duplicate producer sends)
- Invalid state transition warnings (may indicate producer bugs)
- Message processing lag

### Alerting Recommendations

- **Critical:** DLQ message rate spike (investigate immediately)
- **Critical:** Circuit breaker open for > 5 minutes (infrastructure issue)
- **Warning:** Health check failures (may indicate transient issues)
- **Info:** Idempotency skip rate increase (may indicate producer retry behavior)

### Operational Procedures

**When Messages Appear in DLQ:**
1. Monitor DLQ topic for failed messages
2. Analyze error reasons and identify patterns
3. Fix root cause (restore infrastructure, fix data, update code)
4. Replay messages from DLQ to main topic using same orderId key
5. Verify messages processed successfully

**When Circuit Breaker Opens:**
1. Check Kafka broker health
2. Review recent deployment changes
3. Check network connectivity
4. Wait for automatic recovery (30s) or manually restore Kafka
5. Circuit breaker will automatically test and close

---

## References

For detailed implementation specifics, refer to:
- **Producer Error Handling:** `producer/docs/ERRORS.md`
- **Consumer Error Handling:** `consumer/docs/ERRORS.md`
- **Producer Configuration:** `producer/docs/CONFIG.md`
- **Consumer Configuration:** `consumer/docs/CONFIG.md`

---

**Document Version:** 1.0  
**Last Updated:** 2026-01-04  
**Maintained By:** E-commerce Platform Team
