# Error Handling Strategy

The Producer (Cart Service) implements a layered error handling strategy across the **API Layer**, **Service Layer**, and **Infrastructure Layer** to ensure reliability and data integrity.

---

## 1. Infrastructure & Kafka Errors (The "Active" Layer)

These errors involve connection to the broker and message delivery. The system handles them through a multi-stage recovery process.

### Level 1: Background Buffering (Time-Based Retries)
- **Action**: The producer is configured for **indefinite retries** (`retries=2147483647`) within a fixed time window.
- **Time Limit**: Kafka will attempt to deliver the message for up to **120 seconds** (`delivery.timeout.ms=120000`).
- **Backoff**: Uses an **exponential backoff** starting at 100ms, preventing a "thundering herd" effect on a recovering broker.
- **Safety**: With `enable.idempotence=true`, Kafka ensures that even if a resend occurs due to a lost acknowledgment, no duplicate orders are created.
- **Goal**: Handle temporary network glitches and broker restarts without losing order sequence or data.

### Level 2: Circuit Breaker (Fail-Fast Mechanism)
- **Action**: Protected by **Resilience4j**, the system monitors the failure rate of Kafka calls.
- **Tripping the Circuit**: If **50% of calls fail** within a sliding window of 10 attempts, the circuit opens for **30 seconds**.
- **Behavior**: When the circuit is **OPEN**, the system immediately rejects new Kafka calls without attempting them, protecting application threads from exhaustion.
- **API Response**: The `GlobalExceptionHandler` returns a **503 Service Unavailable** status, informing the client that the service is temporarily unable to handle requests.

### Level 3: Synchronous Timeout & Data Safety
- **API Timeout**: While Kafka retries in the background for 120s, the API only waits **10 seconds** (`producer.send.timeout.ms`). This ensures the user receives a fast response even if Kafka is slow.
- **Manual Recovery Fallback**: If the 10s timeout is reached OR the Circuit Breaker is open, the system logs the **full order details** to a dedicated `failed-orders.log` file.
- **Goal**: Ensure no customer data is lost. This allows an administrator to re-process the orders once the Kafka cluster is restored.

---

## 2. Resiliency & Consistency Patterns

To ensure the Producer remains a "source of truth," we implement advanced patterns in the `OrderService`.

### Local Store Consistency (Save-with-Rollback)
- **Responsiveness**: We use a 10s synchronous API timeout to ensure the application remains responsive even during broker outages.
- **Rollback Logic**: If Kafka fails after the 10s timeout or due to an open circuit, we revert the in-memory `orderStore`:
    - **Create**: The failed order is **removed** from the store.
    - **Update**: The **previous version** of the order is restored.
- **Consistency Guarantee**: This ensures the local state doesn't "lie" to the user. If the client receives a 500/503 error, they can safely retry the request knowing the system state has been reverted to its pre-failure condition.

### Internal Dead Letter Storage (DLQ)
- **Corrective Action**: Failed messages are added to an in-memory `failedKafkaMessages` map (keyed by `orderId`).
- **Deduplication**: Using a map ensures that only the **latest intended state** of a failed order is preserved for recovery.
- **Data Preservation**: This follows the "Saving for Later Processing" approach to ensure no order data is lost even if the infrastructure is down.

---

## 3. Business Logic Errors

Handled at the service level before any message is sent to Kafka.

- **Order Existence Check**:
    - **Errors**: `OrderNotFoundException` (during update) or `DuplicateOrderException` (during creation).
    - **Handling**: Caught by `GlobalExceptionHandler`, returning **404 Not Found** or **409 Conflict**.
- **Recovery**: No resending occurs here as these are logical errors; the client must correct the request data.

---

## 4. Validation & Syntax Errors

Represent malformed data from the client.

- **Action**: Handled by Spring Bean Validation and the `GlobalExceptionHandler`.
- **Response**: Returns **400 Bad Request** with field-level details (e.g., "numItems is required").
- **Recovery**: The system rejects the request immediately to prevent "poison pill" messages from entering the Kafka stream.

---

## 5. System Health & Proactivity

The system includes proactive monitoring to prevent failures before they occur.

- **Readiness Probes**: The `/health/ready` endpoint actively checks connectivity to Kafka and topic existence.
- **Action**: If the broker is down, the probe returns **503 Service Unavailable**. This allows external load balancers (or Kubernetes) to stop sending traffic to this instance before an actual order fails.
- **Liveness Probes**: The `/health/live` endpoint checks the service's internal state, ensuring the JVM is healthy.

---

## 6. Future Enhancements (Planned)

To move beyond returning errors and improve automated recovery, the following patterns are planned:

1. **External Local Persistence (Fallback)**:
    - In the `catch` block of `KafkaProducerService`, save failed orders to a persistent external database or file (e.g., H2 or a JSON file) to survive application restarts.
    - A background task will periodically attempt to "re-produce" these failed orders once the broker is back online.

2. **Client-Side Retry Header**:
    - Include a `Retry-After` header in **503 Service Unavailable** responses to inform clients exactly how long to wait before retrying.

---

## Error Response Examples

### 400 Validation
```json
{
  "message": "Validation error",
  "errors": {
    "numItems": "numItems is required"
  }
}
```

### 503 Service Unavailable (Circuit Breaker Open)
```json
{
  "timestamp": "2025-12-28T12:00:00.000Z",
  "error": "Service Unavailable",
  "message": "Kafka service is temporarily unavailable (Circuit Breaker Open)",
  "path": "/cart-service/create-order",
  "details": {
    "type": "CIRCUIT_BREAKER_OPEN",
    "orderId": "ORD-000A"
  }
}
```

### 500 Kafka Timeout (Background Buffering Active)
```json
{
  "error": "Failed to publish message",
  "type": "TIMEOUT",
  "orderId": "ORD-000A"
}
```
