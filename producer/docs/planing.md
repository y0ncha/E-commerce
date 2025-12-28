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
```text
producer/
├── pom.xml
├── src/main/
│   ├── java/mta/eda/producer/
│   │   ├── ProducerApplication.java          # Spring Boot entry point
│   │   ├── controller/
│   │   │   └── OrderController.java           # REST endpoints
│   │   ├── service/
│   │   │   ├── OrderService.java              # Business coordination
│   │   │   └── KafkaProducerService.java      # Kafka publishing
│   │   ├── model/
│   │   │   ├── OrderEvent.java                # Kafka event payload
│   │   │   ├── CreateOrderRequest.java        # POST DTO
│   │   │   └── UpdateOrderRequest.java        # PUT DTO
│   │   └── exception/
│   │       └── GlobalExceptionHandler.java    # API error handling
│   └── resources/
│       └── application.properties             # Kafka + app config
├── Dockerfile
└── docker-compose.yml
```
---

## Data Model (Minimal by Design)

### OrderEvent (Kafka Message)
```json
{
  "orderId": "string",
  "status": "CREATED | UPDATED"
}
```

## Sufficiency of the Minimal Structure

This structure is sufficient to:
- Demonstrate ordering guarantees
- Demonstrate broadcast to consumers
- Validate correct Kafka usage

---

## Implementation Phases

### Phase 1 – Setup & Configuration
- Configure Spring Boot with:
  - `spring-boot-starter-web`
  - `spring-boot-starter-kafka`
- Configure Kafka producer properties:
  - `acks=all`
  - `retries=3`
  - `max.in.flight.requests.per.connection=1`
- Define topic name via configuration (`kafka.topic.name` in `application.properties`)
- Use JSON serialization

---

### Phase 2 – REST API

#### Endpoints

- **POST `/api/create-order`**
  - Input: `{ "orderId": "string", "numItems": number }`
  - Behavior:
    - Validate input
    - Create `OrderEvent { orderId, status = CREATED }`
    - Publish to Kafka
    - Return `202 Accepted` on success

- **PUT `/api/update-order`**
  - Input: `{ "orderId": "string", "status": "string" }`
  - Behavior:
    - Validate input
    - Create `OrderEvent { orderId, status = UPDATED }`
    - Publish to Kafka
    - Return `202 Accepted` on success

---

### Phase 3 – Kafka Publishing
- Use `KafkaTemplate<String, OrderEvent>`
- **CRITICAL:** Use `orderId` as the message key
- Topic configuration:
  - In `application.properties`: `kafka.topic.name=order-events`
  - In code (`KafkaProducerService`): `@Value("${kafka.topic.name}") private String topicName;` and `kafkaTemplate.send(topicName, orderId, orderEvent)`
- Rationale:
  - Kafka guarantees ordering per partition
  - Keying by `orderId` ensures all events of the same order go to the same partition
  - “Broadcast” = multiple consumer groups, each group receives the full stream

---

### Phase 4 – Error Handling
- Validation errors → `400 Bad Request`
- Kafka unavailable / send failure → `500 Internal Server Error`
- API returns success **only after Kafka ACK**
- All failures are logged

No retries or fallbacks at the API layer beyond Kafka’s producer retries.

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