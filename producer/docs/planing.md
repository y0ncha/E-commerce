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

cd ..## Project Structure (Current Implementation)
```text
producer/
├── pom.xml
├── src/main/
│   ├── java/mta/eda/producer/
│   │   ├── Producer.java                      # Spring Boot entry point
│   │   ├── controller/
│   │   │   └── OrderController.java           # REST endpoints
│   │   ├── service/
│   │   │   ├── OrderService.java              # Business logic & coordination
│   │   │   ├── KafkaProducerService.java      # Kafka publishing layer
│   │   │   └── OrderUtils.java                # Order generation utilities
│   │   ├── model/
│   │   │   ├── Order.java                     # Full order domain model (Kafka payload)
│   │   │   ├── OrderItem.java                 # Order line item
│   │   │   ├── CreateOrderRequest.java        # POST /create-order DTO
│   │   │   └── UpdateOrderRequest.java        # PUT /update-order DTO
│   │   ├── config/
│   │   │   ├── KafkaProducerConfig.java       # Kafka producer configuration
│   │   │   └── KafkaTopicConfig.java          # Topic auto-creation
│   │   └── exception/
│   │       ├── GlobalExceptionHandler.java    # Centralized error handling
│   │       ├── ProducerSendException.java     # Kafka send failure exception
│   │       ├── DuplicateOrderException.java   # Business rule violation
│   │       ├── OrderNotFoundException.java    # Business rule violation
│   │       └── README.md                      # Error handling documentation
│   └── resources/
│       └── application.properties             # Kafka + app config
├── Dockerfile
└── docker-compose.yml
```
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
record OrderItem(String itemId, int quantity, double price)
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

### Phase 1 – Setup & Configuration ✅ IMPLEMENTED
- Spring Boot dependencies:
  - `spring-boot-starter-web` (REST API)
  - `spring-boot-starter-kafka` (Kafka integration)
  - `spring-boot-starter-validation` (Bean validation)
  - `lombok` (reduce boilerplate)
- Kafka producer configuration (`KafkaProducerConfig`):
  - `acks=all` (ensure all replicas acknowledge)
  - `enable.idempotence=true` (prevent duplicates)
  - `retries=3` (automatic retry on transient failures)
  - `max.in.flight.requests.per.connection=5` (safe with idempotence)
  - JSON serialization for Order objects
- Topic configuration (`KafkaTopicConfig`):
  - Topic name: `order-events` (configurable via `kafka.topic.name`)
  - Auto-creation with default partitions and replication
- Send timeout: Configurable via `producer.send.timeout.ms` (default: 10 seconds)

---

### Phase 2 – REST API ✅ IMPLEMENTED

#### Endpoints

- **GET `/api` or `/api/`** (Root/Health)
  - Purpose: Service information and health status (combined endpoint)
  - Response: Service name, overall status, component health, available endpoints
  - Status Codes:
    - `200 OK` - Service and Kafka are healthy
    - `503 Service Unavailable` - Kafka is unreachable
  - Example Response (Healthy):
    ```json
    {
      "service": "Producer (Cart Service)",
      "status": "UP",
      "timestamp": "2025-12-28T10:30:00Z",
      "components": {
        "service": {
          "status": "UP",
          "message": "Producer service is running"
        },
        "kafka": {
          "status": "UP",
          "message": "Kafka is reachable and topic exists"
        }
      },
      "endpoints": {
        "createOrder": "POST /api/create-order",
        "updateOrder": "PUT /api/update-order"
      }
    }
    ```
  - Example Response (Degraded):
    ```json
    {
      "service": "Producer (Cart Service)",
      "status": "DEGRADED",
      "timestamp": "2025-12-28T10:30:00Z",
      "components": {
        "service": {
          "status": "UP",
          "message": "Producer service is running"
        },
        "kafka": {
          "status": "DOWN",
          "message": "Kafka is unreachable or topic does not exist"
        }
      },
      "endpoints": {
        "createOrder": "POST /api/create-order",
        "updateOrder": "PUT /api/update-order"
      }
    }
    ```

- **POST `/api/create-order`**
  - Input: `{ "orderId": "string", "numItems": number }`
  - Validation:
    - `orderId` is required and not blank
    - `numItems` must be between 1 and 50
  - Behavior:
    - Validates input (Bean Validation)
    - Generates complete Order with:
      - Random customer ID
      - ISO-8601 timestamp
      - Auto-generated order items (random IDs, quantities, prices)
      - Calculated total amount
      - Status = "new"
    - Stores order in-memory (ConcurrentHashMap)
    - Publishes full Order to Kafka (key = orderId)
    - Returns `202 Accepted` on success with Order payload
  - Error handling:
    - `400 Bad Request` - Invalid input
    - `409 Conflict` - Duplicate orderId
    - `500 Internal Server Error` - Kafka send failure

- **PUT `/api/update-order`**
  - Input: `{ "orderId": "string", "status": "string" }`
  - Validation:
    - `orderId` is required and not blank
    - `status` is required and not blank
  - Behavior:
    - Validates input (Bean Validation)
    - Looks up existing order in memory
    - Creates updated Order with new status
    - Updates in-memory store
    - Publishes updated Order to Kafka (key = orderId)
    - Returns `202 Accepted` on success with Order payload
  - Error handling:
    - `400 Bad Request` - Invalid input
    - `404 Not Found` - Order doesn't exist
    - `500 Internal Server Error` - Kafka send failure

---

### Phase 3 – Kafka Publishing ✅ IMPLEMENTED
- Use `KafkaTemplate<String, Order>`
- **CRITICAL:** Use `orderId` as the message key
- Topic configuration:
  - In `application.properties`: `kafka.topic.name=order-events`
  - In code (`KafkaProducerService`): `@Value("${kafka.topic.name}") private String topicName;` and `kafkaTemplate.send(topicName, orderId, order)`
- Synchronous send with timeout:
  - `.send(topic, key, value).get(timeout, TimeUnit.MILLISECONDS)`
  - Configurable timeout via `producer.send.timeout.ms` (default: 10 seconds)
  - Blocks until Kafka acknowledges or timeout/error occurs
- Rationale:
  - Kafka guarantees ordering per partition
  - Keying by `orderId` ensures all events of the same order go to the same partition
  - "Broadcast" = multiple consumer groups, each group receives the full stream
  - Synchronous send ensures API returns success only after Kafka ACK
- Error classification:
  - `TIMEOUT` - Send exceeded timeout threshold
  - `INTERRUPTED` - Thread interrupted during send
  - `KAFKA_ERROR` - Broker/serialization/acknowledgment failure
  - `UNEXPECTED` - Other errors

---

### Phase 4 – Error Handling ✅ IMPLEMENTED

#### Exception Handling Strategy
All exceptions are handled by `GlobalExceptionHandler` with appropriate HTTP status codes:

1. **Validation Errors (400 Bad Request)**
   - Exception: `MethodArgumentNotValidException`
   - Triggers: Invalid request body (missing/invalid fields, constraint violations)
   - Response: Field-level error details

2. **Order Not Found (404 Not Found)**
   - Exception: `OrderNotFoundException`
   - Triggers: Attempting to update non-existent order
   - Response: Error message with orderId

3. **Duplicate Order (409 Conflict)**
   - Exception: `DuplicateOrderException`
   - Triggers: Creating order with existing orderId
   - Response: Error message with orderId

4. **Kafka Send Failures (500 Internal Server Error)**
   - Exception: `ProducerSendException`
   - Triggers: Kafka connectivity/timeout/serialization errors
   - Error types:
     - `TIMEOUT` - Send operation exceeded timeout threshold
     - `INTERRUPTED` - Thread was interrupted during send
     - `KAFKA_ERROR` - Broker connectivity, serialization, or acknowledgment failure
     - `UNEXPECTED` - Other unexpected errors
   - Response: Error type, orderId, and message
   - Additional behavior:
     - Restores interrupt flag on `InterruptedException`
     - Logs distinct error types for monitoring
     - Provides structured error response for clients

5. **Generic Errors (500 Internal Server Error)**
   - Exception: `Exception` (fallback handler)
   - Triggers: Any unhandled exception
   - Response: Generic error message

#### Error Flow
```
Client Request
    ↓
Controller (@Valid validates input)
    ↓ (validation fails)
    ├─→ MethodArgumentNotValidException → 400
    ↓
OrderService (business logic)
    ↓ (duplicate order)
    ├─→ DuplicateOrderException → 409
    ↓ (order not found)
    ├─→ OrderNotFoundException → 404
    ↓
KafkaProducerService (publish to Kafka)
    ↓ (Kafka unavailable/timeout/error)
    ├─→ ProducerSendException → 500
    ↓
Success → 202 Accepted
```

#### Key Features
- API returns success **only after Kafka ACK**
- All failures are logged with appropriate severity
- Structured error responses for client debugging
- No retries at API layer (relies on Kafka producer retries)
- Thread interrupts are properly handled and restored
- Timeout prevents indefinite blocking

---

### Phase 5 – Testing
- **Unit tests:**
  - Input validation
  - `OrderEvent` creation
- **Integration tests:**
  - Successful request returns `202`
  - Invalid input returns `400`
  - Kafka unavailable returns `500`
- **Manual tests:**
  - Produce events and inspect topic via `kafka-console-consumer`

---

### Phase 6 – Docker
- Docker Compose includes:
  - Zookeeper
  - Kafka broker
  - Producer service
- Producer connects to Kafka via the container network
- Validate end-to-end behavior in Docker

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