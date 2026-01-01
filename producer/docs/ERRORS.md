# Error Handling Strategy

The Producer (Cart Service) implements a layered error handling strategy across the **API Layer**, **Service Layer**, and **Infrastructure Layer** to ensure reliability and data integrity.

---

## 1. Infrastructure & Kafka Errors (The "Active" Layer)

These errors involve connection to the broker and message delivery. The system handles them through a multi-stage recovery process.

### Level 1: Synchronous Online Retry (Response Accuracy)
- **Action**: The producer is configured for **indefinite retries** (`retries=2147483647`) within a fixed time window.
- **Time Limit**: Kafka will attempt to deliver the message for **8 seconds** (`delivery.timeout.ms=8000`).
- **Per-Attempt Timeout**: Each individual request attempt times out after **3 seconds** (`request.timeout.ms=3000`), allowing for ~2-3 retry attempts within the 8s window.
- **Goal**: Ensure that if a user receives a failure response (after the 10s API timeout), the Kafka client has **already stopped** trying to send the message. This prevents "Ghost Successes" where an order lands in Kafka after the user was told it failed.

### Level 2: Circuit Breaker (Fail-Fast Mechanism)
- **Action**: Protected by **Resilience4j**, the system monitors the failure rate of Kafka calls.
- **Tripping the Circuit**: If **50% of calls fail** within a sliding window of 10 attempts, the circuit opens for **30 seconds**.
- **Behavior**: When the circuit is **OPEN**, the system immediately rejects new Kafka calls without attempting them, protecting application threads from exhaustion.
- **API Response**: The `GlobalExceptionHandler` returns a **503 Service Unavailable** status, informing the client that the service is temporarily unable to handle requests.

### Level 3: Data Safety (Manual Recovery Fallback)
- **Action**: If the timeout is reached OR the Circuit Breaker is open, the system logs the **full order details** to a dedicated `failed-orders.log` file.
- **Implementation**: A dedicated logger (`FAILED_ORDERS_LOGGER`) captures the order payload and failure reason.
- **Goal**: Ensure no customer data is lost. This allows an administrator to re-process the orders once the Kafka cluster is restored.

---

## 2. Resiliency & Consistency Patterns

To ensure the Producer remains a "source of truth," we implement advanced patterns in the `OrderService`.

### Local Store Consistency (Save-with-Rollback)
- **Responsiveness**: We use a 10s synchronous API timeout (`producer.send.timeout.ms`) to ensure the application remains responsive even during broker outages.
- **Rollback Logic**: If Kafka fails after the timeout or due to an open circuit, we revert the in-memory `orderStore`:
    - **Create**: The failed order is **removed** from the store.
    - **Update**: The **previous version** of the order is restored.
- **Consistency Guarantee**: This ensures the local state doesn't "lie" to the user. If the client receives a 503 error, they can safely retry the request knowing the system state has been reverted to its pre-failure condition.

### Internal Dead Letter Storage (DLQ)
- **Corrective Action**: Failed messages are added to an in-memory `failedMessages` map (keyed by `orderId`).
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

## Error Response Examples

### 400 Validation
```json
{
  "timestamp": "2026-01-01T12:00:00.000Z",
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

### 503 Service Unavailable (Circuit Breaker Open or Kafka Timeout)
```json
{
  "timestamp": "2026-01-01T12:00:00.000Z",
  "error": "Service Unavailable",
  "message": "The service is temporarily unable to process your request. Please try again later.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "KAFKA_UNAVAILABLE",
    "orderId": "ORD-000A"
  }
}
```
