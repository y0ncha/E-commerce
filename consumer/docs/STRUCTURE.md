# STRUCTURE.md: Consumer Project Structure

## Directory Layout

```
consumer/
├── src/main/java/mta/eda/consumer/
│   ├── config/
│   │   └── KafkaConsumerConfig.java          # Kafka consumer factory configuration
│   │
│   ├── controller/
│   │   └── OrderController.java              # REST API endpoints (root, health, orders)
│   │
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java       # Centralized exception handling (6 handlers)
│   │   ├── OrderNotFoundException.java       # Custom exception for 404 errors
│   │   └── InvalidOrderIdException.java      # Custom exception for validation errors
│   │
│   ├── model/
│   │   ├── order/
│   │   │   ├── Order.java                    # Order record (from Kafka events)
│   │   │   ├── OrderItem.java                # OrderItem record (line items)
│   │   │   └── ProcessedOrder.java           # ProcessedOrder record (Order + shipping cost)
│   │   │
│   │   ├── request/
│   │   │   ├── OrderDetailsRequest.java      # DTO with orderId validation (hex format)
│   │   │   └── AllOrdersFromTopicRequest.java # DTO with topicName validation
│   │   │
│   │   └── response/
│   │       ├── HealthCheck.java              # HealthCheck record (status + details)
│   │       └── HealthResponse.java           # HealthResponse record (comprehensive health info)
│   │
│   └── service/
│       ├── general/
│       │   └── HealthService.java            # Health check operations (service, Kafka, state)
│       │
│       ├── kafka/
│       │   └── KafkaConsumerService.java     # Kafka listener (@KafkaListener)
│       │
│       ├── order/
│       │   └── OrderService.java             # Order processing logic (idempotency, sequencing, shipping)
│       │
│       └── utils/
│           └── OrderUtils.java               # Utility methods (shipping cost, orderId normalization)
│
├── src/main/resources/
│   └── application.properties                # Spring Boot configuration (Kafka, logging)
│
├── docs/
│   ├── PLAN.md                               # Implementation phases and progress
│   ├── CONFIG.md                             # Configuration options and rationale
│   ├── ERRORS.md                             # Error handling mechanisms
│   └── STRUCTURE.md                          # This file
│
├── Dockerfile                                # Multi-stage build (Maven + JRE)
├── docker-compose.yml                        # Docker orchestration (order-service only)
├── .env.example                              # Environment variables template
├── pom.xml                                   # Maven dependencies
├── HELP.md                                   # Quick reference guide
└── README.md                                 # Comprehensive documentation
```

---

## Key Files Description

### **config/KafkaConsumerConfig.java**
- Configures `ConsumerFactory` with StringDeserializers
- Sets up `ConcurrentKafkaListenerContainerFactory`
- Enables **MANUAL_IMMEDIATE** acknowledgment mode
- Provides `ObjectMapper` bean for JSON deserialization

### **exception/GlobalExceptionHandler.java**
Centralized exception handler for all API errors with consistent error responses:

**6 Exception Handlers:**
1. `MethodArgumentNotValidException` → HTTP 400 (validation errors, field errors)
2. `HttpMessageNotReadableException` → HTTP 400 (malformed JSON)
3. `InvalidOrderIdException` → HTTP 400 (custom exception, orderId validation)
4. `IllegalArgumentException` → HTTP 400 (OrderUtils validation failures)
5. `OrderNotFoundException` → HTTP 404 (order not found in state store)
6. `Exception` (catch-all) → HTTP 500 (unhandled exceptions, stack trace logged)

**Logging Levels:**
- WARN for 400 errors (client's fault)
- INFO for 404 errors (expected, not found)
- ERROR for 500 errors (with full stack trace)

### **exception/OrderNotFoundException.java**
Custom exception thrown when order not found:
```java
public class OrderNotFoundException extends RuntimeException {
    private final String orderId;
    public String getOrderId() { ... }
}
```

### **exception/InvalidOrderIdException.java**
Custom exception for invalid orderId format:
```java
public class InvalidOrderIdException extends RuntimeException {
    private final String orderId;
    public String getOrderId() { ... }
}
```
- **Root Endpoint** (GET /order-service/): Service metadata with structured endpoints
- **Live Probe** (GET /order-service/health/live): Liveness check via HealthService
- **Ready Probe** (GET /order-service/health/ready): Readiness check with Kafka connectivity
- **Order Details** (POST /order-service/order-details): Get order by ID with validation
- **All Orders** (POST /order-service/getAllOrdersFromTopic): Diagnostic endpoint

All endpoints use dependency injection of `OrderService` and `HealthService`.

### **model/order/Order.java**
Record representing an order event from Kafka:
```java
public record Order(
    String orderId,
    String customerId,
    LocalDateTime orderDate,
    List<OrderItem> items,
    double totalAmount,
    String currency,
    String status
) {}
```

### **model/order/ProcessedOrder.java**
Record wrapping Order with calculated shipping cost:
```java
public record ProcessedOrder(
    Order order,
    double shippingCost
) {}
```
Uses **composition pattern** (not inheritance) to keep Order immutable.

### **model/request/OrderDetailsRequest.java**
DTO with comprehensive orderId validation:
- `@NotBlank` - Ensures orderId is not empty
- `@Pattern(regexp = "^[0-9A-Fa-f]+$")` - Validates hexadecimal format
- `@JsonProperty("orderId")` - Explicit JSON mapping

### **model/response/HealthResponse.java**
Comprehensive health response record:
```java
public record HealthResponse(
    String serviceName,
    String type,           // "liveness" or "readiness"
    String status,         // "UP" or "DOWN"
    String timestamp,      // ISO-8601 format
    Map<String, HealthCheck> checks
) {}
```

### **service/general/HealthService.java**
Health check service with three methods:
- `getServiceStatus()` - Service responsiveness
- `getKafkaStatus()` - Kafka broker connectivity
- `getLocalStateStatus()` - Local state store accessibility

### **service/kafka/KafkaConsumerService.java**
Message listener with full error handling:
- `@KafkaListener(topics = "${kafka.consumer.topic}", ...)`
- JSON deserialization with `ObjectMapper`
- Message key validation
- Manual acknowledgment after successful processing

### **service/order/OrderService.java**
Core event processing logic:
- **Idempotency Check** - Detects duplicate events
- **Sequencing Validation** - Enforces state machine (CREATED → CONFIRMED → DISPATCHED → DELIVERED)
- **Shipping Cost Calculation** - Business logic: $5 + ($0.50 × items) + (2% × total)
- **State Management** - Maintains `processedOrderStore` map
- **Error Handling** - Logs invalid transitions

---

## Package Organization

| Package | Purpose | Responsibility |
|---------|---------|-----------------|
| **config** | Configuration | Bean setup, Kafka factory |
| **controller** | API Layer | HTTP endpoints, request/response handling |
| **exception** | Error Handling | GlobalExceptionHandler, custom exceptions |
| **model.order** | Domain Objects | Order entities (records) |
| **model.request** | Input DTOs | Request validation |
| **model.response** | Output DTOs | Response objects |
| **service.general** | Cross-cutting | Health checks |
| **service.kafka** | Kafka Integration | Message consumption, deserialization |
| **service.order** | Business Logic | Processing, validation, state management |
| **service.utils** | Utilities | Shipping cost calculation, orderId normalization |

---

## Data Flow

```
Kafka Topic (orders)
    ↓
KafkaConsumerService.listen()
    ├─ Receive ConsumerRecord<String, String>
    ├─ Deserialize JSON → Order object
    ├─ Validate message key
    ↓
OrderService.processOrder(Order)
    ├─ Check Idempotency (exact duplicate?)
    ├─ Check Sequencing (valid transition?)
    ├─ Calculate Shipping Cost
    ├─ Update State (ProcessedOrder)
    ↓
acknowledgment.acknowledge()
    ↓
Kafka Offset Committed
```

---

## Interaction Diagram

```
Client Request
    ↓
OrderController
    ├─ Injects: OrderService, HealthService
    ├─ Validates: Request DTOs (@NotBlank, @Pattern)
    ↓
OrderService / HealthService
    ├─ Retrieves: ProcessedOrder, HealthStatus
    ↓
Response DTO (HealthResponse, Map)
    ↓
Client Response (JSON)
```

---

## Design Patterns Used

1. **Composition** - ProcessedOrder wraps Order (not inheritance)
2. **Dependency Injection** - Spring @Autowired in controllers and services
3. **DTO Pattern** - Request/Response records for data transfer
4. **Service Layer** - Business logic isolated in services
5. **State Machine** - Sequencing validation for order status
6. **Error Handling** - Try-catch with comprehensive logging
7. **Kafka Manual Acknowledgment** - Ensures At-Least-Once delivery

