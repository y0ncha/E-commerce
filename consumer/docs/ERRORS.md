# Error Handling Strategy (Consumer)

The Consumer (Order Service) implements a comprehensive error handling strategy across the **Message Processing Layer**, **State Management Layer**, and **Business Logic Layer** to ensure reliability, idempotency, and consistency in distributed event processing.

---

## 1. Message Reception & Deserialization Errors (The "Gate Keeper" Layer)

These errors occur when messages arrive from Kafka but fail to be converted into valid `Order` objects. The system handles them through a multi-stage filtering process.

### Level 1: JSON Deserialization Failure (Poison Pill Handling)
- **Error Trigger**: When `ObjectMapper.readValue()` fails to parse the Kafka message value into an `Order` record (malformed JSON, missing fields, type mismatches).
- **Handling**:
  - The exception is caught in `OrderConsumer.listen()` as `JsonProcessingException`.
  - The full raw message is logged with error details for debugging and audit trails.
  - **Critical**: The message is **acknowledged immediately** despite the failure, moving the consumer offset forward.
- **Rationale**: This prevents the consumer from getting stuck in an infinite retry loop on a "poison pill" message (corrupted/malformed data that will never deserialize). The message is lost, but the consumer keeps processing.
- **Log Example**:
  ```
  ERROR: Failed to deserialize message: {"invalid": "json"...
  ```
- **API Impact**: No API exposure (this occurs during asynchronous message processing). The order is never added to the local state.

### Level 2: Message Key Validation (Data Integrity Check)
- **Validation**: After successful deserialization, the consumer verifies that the Kafka message key matches the `orderId` in the message body.
- **Mismatch Behavior**: If `key != order.orderId()`, a warning is logged but processing continues (lenient validation).
- **Rationale**: Kafka partitioning relies on the key for ordering. A mismatch indicates a potential data inconsistency upstream (e.g., producer bug) but doesn't block processing of valid order data.
- **Log Example**:
  ```
  WARN: Message key ORD-123 does not match order body id ORD-456
  ```

---

## 2. State Management Layer: Idempotency & Sequencing (The "Gatekeeper" for State)

These mechanisms prevent duplicate processing and enforce valid state transitions, ensuring the consumer acts as a reliable mirror of the producer's state.

### Level 1: Idempotency Check (Duplicate Detection)
- **Mechanism**: `OrderService.processOrder()` performs an **exact equality check** on the incoming `Order` against the current stored `ProcessedOrder`.
- **Implementation**:
  ```java
  if (current != null && current.order().equals(order)) {
      logger.info("Duplicate event: Order {} already in state {}. Skipping.", orderId, order.status());
      return;
  }
  ```
- **Trigger**: Activated when:
  - The `orderId` already exists in `processedOrderStore`
  - **AND** all Order fields are identical (same status, items, amount, date, etc.)
- **Action**: The message is skipped without any state update.
- **Rationale**: "At-Least-Once" delivery semantics from Kafka mean the same message can be delivered multiple times (e.g., after consumer restart). This check ensures idempotent processing—duplicate events are harmless.
- **Example Scenario**:
  - Message arrives: `Order(id=ORD-001, status=CREATED, items=[...], totalAmount=100.00)`
  - Message arrives again (duplicate): Same exact order
  - **Result**: Second message is silently skipped; state unchanged.

### Level 2: State Machine Validation (Sequencing Check)
- **Mechanism**: `OrderService.isValidTransition()` enforces a strict **state machine** for order status.
- **Valid State Transitions**:
  ```
  CREATED -> CONFIRMED -> DISPATCHED -> DELIVERED
  ```
- **Rules**:
  - From `CREATED`: Can transition to `CONFIRMED`, `DISPATCHED`, or `DELIVERED`.
  - From `CONFIRMED`: Can transition to `DISPATCHED` or `DELIVERED`.
  - From `DISPATCHED`: Can only transition to `DELIVERED`.
  - From `DELIVERED`: No transitions allowed (terminal state).
  - Unknown statuses: Default-allow (defensive posture for future extensibility).
- **Implementation**:
  ```java
  private boolean isValidTransition(String currentStatus, String newStatus) {
      if (currentStatus.equals(newStatus)) {
          return true; // Same status (duplicate or no-op)
      }
      return switch (currentStatus) {
          case "CREATED" -> newStatus.equals("CONFIRMED") || newStatus.equals("DISPATCHED") || newStatus.equals("DELIVERED");
          case "CONFIRMED" -> newStatus.equals("DISPATCHED") || newStatus.equals("DELIVERED");
          case "DISPATCHED" -> newStatus.equals("DELIVERED");
          case "DELIVERED" -> false;
          default -> true;
      };
  }
  ```
- **Action on Invalid Transition**:
  - The incoming message is **rejected** without updating state.
  - A warning is logged with the attempted transition details.
- **Rationale**: Prevents "older" events (due to Kafka reordering or out-of-order delivery) from corrupting the state. For example:
  - Current state: `Order(status=DISPATCHED)`
  - Incoming event: `Order(status=CREATED)` (out of order)
  - **Result**: The event is rejected; order remains `DISPATCHED`.
- **Log Example**:
  ```
  WARN: Invalid transition: Order ORD-001 cannot move from DISPATCHED to CREATED. Rejecting update.
  ```
- **API Impact**: The rejected order state is preserved; queries return the last valid state.

### Level 3: Business Logic Processing (Shipping Cost Calculation)
- **Mechanism**: `OrderService.calculateShippingCost()` computes the shipping cost based on order items.
- **Formula**:
  ```
  Shipping Cost = Base Cost ($5) + Item Cost ($0.50 per item) + Percentage Cost (2% of total amount)
  ```
- **Error Handling**: 
  - Invalid/missing order items are safely handled by the stream operation; if items list is null or empty, `itemCount = 0` (no exception).
  - Negative or zero total amounts are processed as-is (no validation error; the order comes from the trusted producer).
- **Rationale**: This is deterministic logic; the same order always produces the same shipping cost. No external dependencies mean no transient failures here.

---

## 3. State Update & Persistence Layer (The "Commit Point")

Once an order passes idempotency and sequencing checks, it must be safely persisted to the local store before acknowledgment.

### Level 1: Atomic State Update
- **Mechanism**: `processedOrderStore.put(orderId, processedOrder)` updates the concurrent map atomically.
- **Data Structure**: `Map<String, ProcessedOrder>` where `ProcessedOrder` wraps the `Order` and its calculated `shippingCost`.
- **Thread Safety**: Uses `ConcurrentHashMap` for lock-free, thread-safe updates.
- **Failure Scenarios**:
  - **Memory Exhaustion**: If the system runs out of memory during `put()`, an `OutOfMemoryError` is thrown. The consumer stops; Kubernetes/Docker will restart it. The message is **not acknowledged**, so it will be reprocessed after restart.
  - **Concurrent Access**: Multiple Kafka listener threads may update different `orderId` keys simultaneously. The map ensures this is safe; no data corruption.

### Level 2: Acknowledgment (Offset Commit)
- **Mechanism**: `Acknowledgment.acknowledge()` is called in `OrderConsumer.listen()` **only after** the state update succeeds.
- **Timing**: 
  ```
  Try {
      deserialize -> validate -> check idempotency -> check sequencing -> calculate shipping -> update state
      [IF ALL SUCCEED]: acknowledge()
  } Catch (JsonProcessingException) {
      acknowledge() // Still acknowledge to move past poison pill
  } Catch (Exception) {
      acknowledge() // Catch-all; log and acknowledge
  }
  ```
- **Rationale**: 
  - If the state update fails, the message is **not acknowledged**, and Kafka will re-deliver it after the consumer restart.
  - This ensures "At-Least-Once" delivery with the guarantee that no order event is lost due to a consumer crash.
- **Log Example**:
  ```
  INFO: Successfully acknowledged message for order ORD-001. Offset: 42
  ```

---

## 4. Query & API Layer Errors

When clients query the consumer's state via REST endpoints (Phase 3), errors are handled gracefully.

### Level 1: Order Not Found (404)
- **Trigger**: Client requests `GET /order-details/{orderId}` for an `orderId` not in `processedOrderStore`.
- **Response**: HTTP **404 Not Found** with a descriptive message.
- **Rationale**: The order has not been received/processed yet, or the `orderId` is invalid.
- **Example Response**:
  ```json
  {
    "timestamp": "2026-01-02T12:00:00.000Z",
    "error": "Not Found",
    "message": "Order with ID ORD-999 not found in the system.",
    "path": "/order-details/ORD-999",
    "details": {
      "orderId": "ORD-999",
      "reason": "Order has not been processed yet or does not exist."
    }
  }
  ```

### Level 2: Kafka Broker Unavailability (503)
- **Trigger**: (Future Phase) If the consumer needs to verify message ordering or re-read from Kafka, and the broker is down.
- **Detection**: A `KafkaHealthService` can monitor the admin client connection.
- **Response**: HTTP **503 Service Unavailable**.
- **Rationale**: The service is temporarily unable to fulfill the request due to infrastructure issues.

---

## 5. Exception Handling & Recovery

### Global Exception Handler (OrderConsumer)
The `OrderConsumer` implements multi-layer exception handling:

| Exception Type | Catch Location | Action | Acknowledgment |
|---|---|---|---|
| `JsonProcessingException` | `OrderConsumer.listen()` | Log error with raw message; proceed | **Yes** (poison pill) |
| `Exception` (catch-all) | `OrderConsumer.listen()` | Log error; proceed | **Yes** (fail-safe) |
| `Exception` in `processOrder()` | Not caught locally | Propagates to `OrderConsumer`; logged | Depends on catch above |

### Recovery Workflow

1. **Deserialization Failure**:
   - Message is logged but acknowledged.
   - Consumer continues processing next message.
   - **Recovery**: Administrator reviews logs; no data is lost (raw message captured).

2. **Invalid State Transition**:
   - Message is logged as warning; order state is preserved.
   - No state corruption occurs.
   - **Recovery**: Automatic (next valid event will succeed).

3. **Duplicate Event**:
   - Message is logged as info; silently skipped.
   - **Recovery**: Automatic (idempotency ensures harmless reprocessing).

4. **Consumer Crash**:
   - Any in-flight message is not acknowledged.
   - Upon restart, Kafka re-delivers the message from the last committed offset.
   - **Recovery**: Automatic (Kafka rebalancing + At-Least-Once semantics).

---

## 6. Error Response Examples

### Deserialization Error (Logged)
```
ERROR [OrderConsumer]: Failed to deserialize message: {"invalid_json"
at com.fasterxml.jackson.databind.ObjectMapper.readValue(...)
Message key: ORD-123
Partition: 0, Offset: 1025
```

### Invalid State Transition (Logged)
```
WARN [OrderService]: Invalid transition: Order ORD-001 cannot move from DISPATCHED to CREATED. Rejecting update.
  Current State: ProcessedOrder(Order(orderId=ORD-001, status=DISPATCHED, ...), shippingCost=15.50)
  Incoming Event: Order(orderId=ORD-001, status=CREATED, ...)
```

### Duplicate Event (Logged)
```
INFO [OrderService]: Duplicate event: Order ORD-001 already in state CONFIRMED. Skipping.
  Previous: ProcessedOrder(Order(..., status=CONFIRMED, ...), shippingCost=10.25)
  Incoming: Order(..., status=CONFIRMED, ...) [identical]
```

### Order Not Found (API Response)
```json
{
  "timestamp": "2026-01-02T12:00:00.000Z",
  "error": "Not Found",
  "message": "Order with ID ORD-999 not found in the system.",
  "path": "/order-details/ORD-999"
}
```

---

## 7. Design Patterns Summary

| Pattern | Implementation | Purpose |
|---|---|---|
| **Idempotency** | `Order.equals()` check before state update | Ensure duplicate messages don't corrupt state |
| **State Machine** | `isValidTransition()` validation | Prevent out-of-order events from degrading state |
| **Poison Pill Handling** | Acknowledge after deserialization failure | Prevent consumer from blocking on malformed messages |
| **At-Least-Once Delivery** | Acknowledge only after successful state update | Guarantee no order events are lost |
| **Fail-Safe Logging** | Log all errors with full context | Enable manual recovery if needed |
| **Defensive Defaults** | Allow unknown status transitions | Future-proof for new order states |

---

## Future Enhancements (Phase 4+)

- **Distributed Tracing**: Add correlation IDs to track orders across producer and consumer.
- **Metrics & Monitoring**: Export Micrometer metrics for idempotency checks, transition failures, and message lag.
- **Dead Letter Queue (DLQ)**: Store irreparably damaged messages (beyond poison pills) for manual inspection.
- **Circuit Breaker on State Access**: If the state store becomes unavailable (edge case), fail gracefully with 503.
- **Event Audit Trail**: Store all received events (even rejected ones) for compliance/debugging.

