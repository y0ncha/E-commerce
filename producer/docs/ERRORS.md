# Error Handling Strategy

The Producer (Cart Service) implements a layered error handling strategy across the **API Layer**, **Service Layer**, and **Infrastructure Layer** to ensure reliability and data integrity.

---

## 1. Infrastructure & Kafka Errors (The "Active" Layer)

These errors involve connection to the broker and message delivery. The system handles them through a multi-stage recovery process.

### Level 1: Native Kafka Retries (Transient Errors)
- **Action**: The producer is configured to automatically attempt to resend messages **12 times** (`retries=12`).
- **Backoff**: Uses an **exponential backoff** starting at 100ms (`retry.backoff.ms=100`), preventing a "thundering herd" effect on a recovering broker.
- **Safety**: With `enable.idempotence=true`, Kafka ensures that even if a resend occurs due to a lost acknowledgment, no duplicate orders are created on the broker.
- **Goal**: Handle temporary network glitches without bothering the user or losing order sequence.

### Level 2: Synchronous Timeout Handling (Broker Outage)
- **Action**: While Kafka retries for up to 120 seconds (`delivery.timeout.ms`), the `KafkaProducerService` limits the API's wait time to **10 seconds** via `.get(sendTimeoutMs, ...)`.
- **Reasoning**: This prevents HTTP requests from hanging indefinitely. If the broker doesn't recover within 10 seconds, the service throws a `ProducerSendException`.
- **API Response**: The `GlobalExceptionHandler` converts this into a **500 Internal Server Error** response, providing the user with a clear error message.

### Level 3: Data Safety (Manual Recovery Fallback)
- **Action**: If the send operation fails (after retries or timeout), the system logs the **full order details** to a dedicated `failed-orders.log` file.
- **Implementation**: A dedicated logger (`FAILED_ORDERS_LOGGER`) captures the order payload and failure reason.
- **Goal**: Ensure no customer data is lost. This allows an administrator to re-process the orders once the Kafka cluster is restored.

---

## 2. Resiliency & Consistency Patterns

To ensure the Producer remains a "source of truth," we implement advanced patterns in the `OrderService`.

### Local Store Consistency (Save-with-Rollback)
- **Responsiveness**: We use a 10s synchronous API timeout to ensure the application remains responsive even during broker outages.
- **Rollback Logic**: If Kafka fails after all native retries and the 10s timeout, we revert the in-memory `orderStore`:
    - **Create**: The failed order is **removed** from the store.
    - **Update**: The **previous version** of the order is restored.
- **Consistency Guarantee**: This ensures the local state doesn't "lie" to the user. If the client receives a 500 error, they can safely retry the request knowing the system state has been reverted to its pre-failure condition.

### Internal Dead Letter Storage (DLQ)
- **Corrective Action**: Failed messages are added to an in-memory `failedKafkaMessages` list.
- **Data Preservation**: This follows the "Saving for Later Processing" approach to ensure no order data is lost even if the infrastructure is down.
- **Note**: In a production environment, this internal list would be replaced by a persistent external database or file to survive application restarts.

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

1. **Circuit Breaker Pattern**:
    - Use **Resilience4j** to "open" a circuit after 5 consecutive Kafka timeouts.
    - This will immediately reject new requests for a short period, allowing the broker time to recover without being hit by 10-second timeout logic on every request.

2. **External Local Persistence (Fallback)**:
    - In the `catch` block of `KafkaProducerService`, save failed orders to a local fallback store (e.g., a local file or H2 database).
    - A background task will periodically attempt to "re-produce" these failed orders once the broker is back online.

3. **Client-Side Retry Header**:
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

### 409 Conflict
```json
{
  "message": "Order ORD-000A already exists"
}
```

### 500 Kafka Timeout
```json
{
  "error": "Failed to publish message",
  "type": "TIMEOUT",
  "orderId": "ORD-000A"
}
```
