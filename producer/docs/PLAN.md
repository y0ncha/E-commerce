# Producer Module – Implementation Plan (Revised & Scoped)

**Project:** Kafka E-commerce Backend – Exercise 2  
**Module:** Producer (Cart Service)  
**Primary Goal:** Publish order events to Kafka **correctly and reliably**, according to the course material  
**Out of Scope:** Rich business modeling, persistence, pricing logic

---

## Kafka Topics
- **Topic:** `order-events`
- **Purpose:** Single stream of all order lifecycle events (create + update). Consumers read the stream and process per `orderId` in-order.
- **Broadcast behavior:** Kafka has no fanout exchange; “broadcast” happens because different consumer groups each get the full stream (each group receives all events).

---

## Scope & Intent

This producer module is intentionally **minimal**.

The implementation focuses on:
- Correct Kafka usage (topics, keys, ordering)
- Correct API behavior
- Clear error handling when Kafka is unavailable

Business-level details (items, pricing, currencies, etc.) are intentionally simplified to keep the focus on **event-driven architecture concepts**, as required by Exercise 02.

---

## Project Structure

See [STRUCTURE.md](./STRUCTURE.md) for complete project structure and component overview.

---

## Data Model (Actual Implementation)

### Order (Kafka Message Payload)
The producer publishes **full Order objects** to Kafka, not minimal events.

```json
{
  "orderId": "ORD-1234",
  "customerId": "CUST-5678",
  "orderDate": "2025-12-28T10:30:00Z",
  "items": [
    {
      "itemId": "ITEM-001",
      "quantity": 2,
      "price": 29.99
    }
  ],
  "totalAmount": 59.98,
  "currency": "USD",
  "status": "new"
}
```

### OrderItem
```java
record OrderItem(String itemId, int quantity, double price){}
```

### API Request DTOs

**CreateOrderRequest** (POST `/api/create-order`):
```json
{
  "orderId": "ORD-1234",
  "numItems": 3
}
```

**UpdateOrderRequest** (PUT `/api/update-order`):
```json
{
  "orderId": "ORD-1234",
  "status": "shipped"
}
```

## Implementation Notes

### Full Order Publishing
- The producer generates a **complete Order** object with items, amounts, and metadata
- This enriched payload is published to Kafka for downstream consumption
- Items are auto-generated with random IDs, quantities, and prices
- Customer ID and order date are generated server-side

### In-Memory State
- Orders are stored in a `ConcurrentHashMap` for update operations
- This enables demonstrating order lifecycle (create → update)
- **Note:** This is for demonstration only; production systems would use a persistent store

### Sufficiency
This structure demonstrates:
- Kafka ordering guarantees (via `orderId` key)
- Broadcast to multiple consumer groups
- Full event payload publishing (realistic for event-driven systems)
- Proper error handling across the stack

---

## Implementation Phases

### Phase 1 – Setup & Configuration ✅ COMPLETED

**What we did:**
- Set up Spring Boot with Kafka, Web, and Validation dependencies
- Configured Kafka producer for durability and ordering (`acks=all`, `enable.idempotence=true`)
- Configured topic `order-events` with 3 partitions and **replication factor 1** (single broker setup)
- Refined performance settings: `linger.ms=5` for throughput batching
- Configured admin client to not fail on Kafka unavailability (`fail-fast=false`)
- Configured logging to suppress Kafka bootstrap spam (Kafka packages set to WARN level)

---

### Phase 2 – REST API ✅ COMPLETED

**What we did:**
- Implemented 5 REST endpoints under `/cart-service` base path
- Health checks: `/health/live` (liveness) and `/health/ready` (readiness with Kafka connectivity check)
- Business endpoints: `POST /create-order` (201 Created) and `PUT /update-order` (200 OK)
- Implemented OrderID normalization: user provides hex characters, system formats as `ORD-####` (e.g., "A" → "ORD-000A")
- Added custom validation for hex-only OrderIDs with `InvalidOrderIdException`
- Synchronous API waits for Kafka acknowledgment before returning success

---

### Phase 3 – Kafka Publishing ✅ COMPLETED

**What we did:**
- Implemented synchronous Kafka publishing with `.get(timeout)` (10 second timeout)
- Used `orderId` as message key to guarantee ordering per partition
- Published full Order objects as JSON to `order-events` topic
- Implemented error classification: TIMEOUT, INTERRUPTED, KAFKA_ERROR, UNEXPECTED
- Ensured API returns success only after Kafka acknowledgment (fail-fast pattern)

---

### Phase 4 – Error Handling ✅ COMPLETED

**What we did:**
- Implemented `GlobalExceptionHandler` with 6 exception types mapped to appropriate HTTP status codes
- Validation errors (400): `MethodArgumentNotValidException`, `InvalidOrderIdException`
- Business errors (404, 409): `OrderNotFoundException`, `DuplicateOrderException`
- Kafka errors (500): `ProducerSendException` with type classification (TIMEOUT, INTERRUPTED, KAFKA_ERROR, UNEXPECTED)
- All exceptions return structured JSON responses with clear error messages
- Thread interrupt handling properly restores interrupt flag

---

### Phase 5 – Docker & Deployment ✅ COMPLETED

**What we did:**
- Created multi-stage Dockerfile with Maven build and OpenJDK 21 runtime
- Configured docker-compose.yml with Zookeeper, Kafka broker, and Producer service
- Made bootstrap servers configurable via `KAFKA_BOOTSTRAP_SERVERS` environment variable
- Set up volume persistence for Zookeeper and Kafka data
- Configured container networking for service discovery (producer connects to `kafka:9092`)

---

### Phase 6 – Advanced Resiliency ✅ COMPLETED

#### TODO 1: Implement a Circuit Breaker (Fail-Fast Mechanism) ✅ COMPLETED
The goal is to stop attempting to call Kafka once a failure threshold is reached, protecting the system from thread exhaustion.

- [x] **Update pom.xml**: Added `resilience4j-spring-boot3` and `spring-boot-starter-aop`.
- [x] **Configure Circuit Breaker in application.properties**: Defined failure rate (50%), sliding window (10), and wait duration (30s).
- [x] **Annotate KafkaProducerService.sendOrder**: Added `@CircuitBreaker` to `sendOrder`.
- [x] **Implement Fallback Logic**: Created `sendOrderFallback` to handle `CallNotPermittedException`, log to DLQ, and throw `CIRCUIT_BREAKER_OPEN`.
- [x] **Update GlobalExceptionHandler**: Added handler for `CIRCUIT_BREAKER_OPEN` to return **503 Service Unavailable**.

#### TODO 2: Increase delivery.timeout.ms (Background Buffering) ✅ COMPLETED
The goal is to allow Kafka's background thread to continue retrying during "network blips" while your API returns a fast response if the individual request exceeds its timeout.

- [x] **Update application.properties**: Increased `spring.kafka.producer.properties.delivery.timeout.ms` to `120000` (2 minutes).
- [x] **Configure Indefinite Retries**: Set `spring.kafka.producer.retries` to `2147483647` (Integer.MAX_VALUE).
- [x] **Align Request Timeouts**: Set `spring.kafka.producer.properties.request.timeout.ms` to `30000`.
- [x] **Verify Configuration Wiring**: Confirmed that `KafkaProducerConfig` correctly retrieves and applies these values.
- [x] **Review API Send Timeout**: Confirmed `producer.send.timeout.ms` (10000) is shorter than background `delivery.timeout.ms`.

---

### Phase 7 – Next Steps & Future Enhancements 📋 PLANNED

#### Optional Future Enhancements (Lower Priority)

1. **JUnit Integration Tests with Testcontainers** (~1 day)
   - [ ] **Unit Tests**: `OrderServiceTest`, `OrderUtilsTest`, `KafkaHealthServiceTest`.
   - [ ] **Integration Tests**: `OrderControllerIntegrationTest` with Testcontainers Kafka.

2. **OpenAPI Documentation**
   - [ ] Add `springdoc-openapi` dependency for Swagger UI.
   - [ ] Configure OpenAPI metadata (title, version, description).
   - [ ] Annotated `OrderController` and DTOs with OpenAPI annotations.

3. **Consumer Module Implementation**
   - [ ] Consume from `order-events` topic.
   - [ ] Process events per order (respecting key-based ordering).
   - [ ] Health checks (consumer lag monitoring).

4. **Monitoring & Observability**
   - [ ] Add Prometheus metrics (Kafka produce latency, success/failure counts).
   - [ ] Add distributed tracing (Spring Cloud Sleuth).

5. **Documentation**
   - [ ] Update README.md with setup/run instructions.
   - [ ] Add architecture diagram (producer-kafka-consumer flow).

---

## Critical Design Decisions (Justified)

### Message Key = `orderId`
Kafka guarantees ordering only within a partition.  
Using `orderId` as the key ensures all events related to the same order are processed sequentially.

### Topic-Based Messaging (Not Fanout)
Kafka topics are used instead of RabbitMQ exchanges.  
Broadcast behavior is achieved because different consumer groups (unique `group.id` per service) each read the full `order-events` stream.

### Fail-Fast API Design
The API returns success only if Kafka confirms receipt of the message, preventing silent message loss.

---

## Out of Scope (Intentionally)
- Persistent storage
- Complex order domain modeling
- Pricing logic
- Avro / Schema Registry (bonus only)

---

## Submission Notes
- The implementation is intentionally scoped to match Exercise 02 requirements
- Additional complexity was avoided to keep the focus on Kafka semantics
- The design follows patterns explicitly taught in the lectures
---

### TL;DR
- This version is:
    - Safer for grading
    - Cleaner
    - Fully aligned with Ex.02
- You can still *talk* about extensibility without *coding* it

If you want next:
- I can rewrite the **consumer planning** the same way
- Or sanity-check this against the **official Ex.02 PDF line by line**
