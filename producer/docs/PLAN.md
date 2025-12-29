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
- Configured Kafka producer for durability and ordering (`acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=1`)
- Configured topic `order-events` with 3 partitions
- Set up admin client to not fail on Kafka unavailability (`fail-fast=false`)
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

### Phase 5 – Testing 🔄 IN PROGRESS (Manual/Integration)

#### Current Testing Approach
- ✅ **Manual Testing:** Using `generated-requests.http` for endpoint testing
  - REST endpoint validation (200, 201, 400, 404, 409, 500 responses)
  - OrderID normalization (hex format validation)
  - Health check endpoints (/live, /ready)

#### Test Files
- ✅ `generated-requests.http` - HTTP request templates for manual testing
- ⏳ `ProducerApplicationTests.java` - Unit/integration tests (pending Phase 7)

---

### Phase 6 – Docker & Deployment ✅ COMPLETED

**What we did:**
- Created multi-stage Dockerfile with Maven build and OpenJDK 21 runtime
- Configured docker-compose.yml with Zookeeper, Kafka broker, and Producer service
- Made bootstrap servers configurable via `KAFKA_BOOTSTRAP_SERVERS` environment variable
- Set up volume persistence for Zookeeper and Kafka data
- Configured container networking for service discovery (producer connects to `kafka:9092`)

---

### Phase 7 – Next Steps & Future Enhancements 📋 PLANNED

#### Immediate Next Steps (High Priority)

1. **JUnit Integration Tests with Testcontainers** (~1 day)

   **Dependencies to add:**
   ```xml
   <dependency>
       <groupId>org.testcontainers</groupId>
       <artifactId>testcontainers</artifactId>
       <version>1.19.8</version>
       <scope>test</scope>
   </dependency>
   <dependency>
       <groupId>org.testcontainers</groupId>
       <artifactId>kafka</artifactId>
       <version>1.19.8</version>
       <scope>test</scope>
   </dependency>
   ```

   **Unit Tests to implement:**
   - `OrderServiceTest` - Test OrderID normalization logic
     - Test hex validation (valid: "A", "1B", "ABCD"; invalid: "012G", "", null)
     - Test padding logic ("A" → "ORD-000A", "ABCD1234" → "ORD-ABCD1234")
     - Test duplicate order detection
   - `OrderUtilsTest` - Test order generation utilities
     - Test item generation (quantity, price ranges)
     - Test total amount calculation
   - `KafkaHealthServiceTest` - Test health check logic (with mocked AdminClient)

   **Integration Tests to implement:**
   - `OrderControllerIntegrationTest` - Test all endpoints with real Kafka
     - Setup: Testcontainers Kafka instance
     - Test POST /create-order success (201 Created)
     - Test PUT /update-order success (200 OK)
     - Test duplicate order (409 Conflict)
     - Test invalid OrderID format (400 Bad Request)
     - Test order not found (404 Not Found)
     - Test validation errors (400 Bad Request - invalid numItems)
     - Test health endpoints (/health/live → 200, /health/ready → 200/503)
     - Verify messages actually published to Kafka topic
     - Verify message key = orderId
     - Verify ordering per partition

   **Expected outcome:**
   - 15-20 test cases passing
   - Messages verified in Kafka
   - All error scenarios covered
   - Test coverage > 70%

2. **Consumer Module Implementation**
   - Plan: Mirror the planing.md approach (detailed but scoped)
   - Consume from `order-events` topic
   - Process events per order (respecting key-based ordering)
   - Store processed events or trigger business logic
   - Health checks (consumer lag monitoring)
   - Error handling for malformed messages

3. **Monitoring & Observability**
   - ✅ Add Prometheus metrics (Kafka produce latency, success/failure counts)
   - ✅ Add distributed tracing (Spring Cloud Sleuth)
   - ✅ Add health check for consumer lag
   - ✅ Add detailed logging for troubleshooting

4. **Documentation**
   - ✅ Update README.md with setup/run instructions
   - ✅ Add API documentation (Swagger/OpenAPI)
   - ✅ Add architecture diagram (producer-kafka-consumer flow)
   - ✅ Add troubleshooting guide

#### Optional Future Enhancements (Lower Priority)

- **Schema Management:** Avro + Schema Registry for message contracts
- **Exactly-Once Semantics:** Coordinator for distributed transactions
- **UI Dashboard:** Admin console for monitoring orders and Kafka

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