# Error Handling Strategy

The Producer (Cart Service) implements a layered error handling strategy across the **API Layer**, **Service Layer**, and **Infrastructure Layer** to ensure reliability and data integrity.

**Course Context**: This error handling strategy aligns with **Session 8 - Error Handling & Resilience** from the MTA Event-Driven Architecture course, implementing patterns for broker connectivity issues, retry logic, and data safety.

---

## Error Handling Architecture Overview

```mermaid
flowchart TD
    Client[Client Request]
    API[API Layer<br/>OrderController]
    Service[Service Layer<br/>OrderService]
    Kafka[Infrastructure Layer<br/>KafkaProducerService]
    CB[Circuit Breaker<br/>Resilience4j]
    Broker[Kafka Broker]
    
    Client -->|HTTP Request| API
    API -->|Validate| API
    API -->|Business Logic| Service
    Service -->|Send Message| Kafka
    Kafka -->|Check Failure Rate| CB
    CB -->|Attempt Send| Broker
    
    Broker -.->|Success ACK| CB
    Broker -.->|Timeout/Error| CB
    
    CB -.->|Success| Kafka
    CB -.->|Failure| Fallback[Fallback Method]
    CB -.->|Circuit Open| Fallback
    
    Fallback -->|Log Failed Order| DLQ[Failed Orders Log]
    Fallback -->|Rollback State| Service
    Fallback -->|Throw Exception| Handler[GlobalExceptionHandler]
    
    Handler -->|HTTP 500/503| Client
    
    style API fill:#e1f5ff
    style Service fill:#ffe1f5
    style Kafka fill:#ffe1e1
    style CB fill:#fff4e1
    style Handler fill:#f5e1ff
```

---

## 1. Infrastructure & Kafka Errors (The "Active" Layer)

These errors involve connection to the broker and message delivery. The system handles them through a multi-stage recovery process.

### Kafka Connectivity Monitoring

**HealthService Implementation:**

The Producer implements proactive Kafka connectivity monitoring through health check endpoints:

- **Readiness Probe** (`GET /cart-service/health/ready`):
  - Checks Kafka broker connectivity before accepting traffic
  - Tests topic existence and accessibility
  - Returns **503 Service Unavailable** if Kafka is unreachable
  - Used by load balancers/Kubernetes to route traffic only to healthy instances

- **Liveness Probe** (`GET /cart-service/health/live`):
  - Checks internal service health (JVM responsiveness)
  - Always returns **200 OK** if service process is running
  - Does not depend on Kafka availability

**Health Check Flow:**

```mermaid
sequenceDiagram
    participant LB as Load Balancer
    participant Health as HealthService
    participant Kafka as Kafka Broker
    participant API as OrderController

    Note over LB,API: Readiness Check Flow
    
    LB->>+Health: GET /health/ready
    Health->>+Kafka: Test connection (1s timeout)
    
    alt Kafka is UP
        Kafka-->>-Health: Connection successful
        Health-->>-LB: HTTP 200 OK<br/>{status: "UP", kafka: "accessible"}
        Note over LB: Route traffic to this instance
    else Kafka is DOWN
        Kafka-->>Health: Timeout / Connection refused
        Health-->>LB: HTTP 503 Service Unavailable<br/>{status: "DOWN", kafka: "unavailable"}
        Note over LB: Remove instance from pool
    end
    
    Note over LB,API: Liveness Check Flow
    
    LB->>+Health: GET /health/live
    Health-->>-LB: HTTP 200 OK<br/>{status: "UP", service: "responsive"}
    Note over LB: Keep instance running
```

**API Response Codes When Kafka is Unreachable:**

1. **Health Endpoint**: Returns **503 Service Unavailable**
   - Indicates service is alive but not ready to handle requests
   - Load balancer stops routing traffic to this instance
   
2. **Order Creation/Update Endpoints**: Return **500 Internal Server Error** or **503 Service Unavailable**
   - **500**: Kafka timeout after exhausting retries (unexpected server condition)
   - **503**: Circuit Breaker open (service protecting itself from cascade failure)
   - Client should implement retry logic with exponential backoff

---

### Level 1: Synchronous Online Retry (Response Accuracy)

**Implementation**: Kafka Producer Client with Internal Retries

- **Action**: The producer is configured for **continuous retries** (MAX_INT retries) within an **8-second delivery window** (`delivery.timeout.ms=8000`).
- **Per-Attempt Timeout**: Each individual request attempt times out after **3 seconds** (`request.timeout.ms=3000`).
- **Application Timeout**: API blocks for **10 seconds** (`producer.send.timeout.ms=10000`).
- **Goal**: Ensure that if a user receives a failure response (after the 10s API timeout), the Kafka client has **already stopped** trying to send the message (at 8s). This prevents "Ghost Successes" where an order lands in Kafka after the user was told it failed.
- **API Response**: 
  - Returns **500 Internal Server Error** if Kafka send fails (ServiceUnavailableException)
  - Returns **503 Service Unavailable** if Circuit Breaker is open (CallNotPermittedException)
- **Architectural Reasoning**: 500 indicates an unexpected server error during the send operation; 503 indicates the service is protecting itself from cascade failures.

**Retry Flow:**

```mermaid
sequenceDiagram
    participant Client
    participant API as OrderController
    participant Service as KafkaProducerService
    participant KafkaClient as Kafka Producer Client
    participant Broker as Kafka Broker

    Client->>+API: POST /create-order
    API->>+Service: sendOrder(orderId, order)
    Service->>+KafkaClient: send(topic, key, value).get(10s)
    
    Note over KafkaClient,Broker: Retry Loop (Internal to Kafka Client)
    
    KafkaClient->>Broker: Attempt 1 (immediate)
    Broker-->>KafkaClient: Timeout (no response)
    
    Note over KafkaClient: Wait 100ms (retry.backoff.ms)
    KafkaClient->>Broker: Retry 1
    Broker-->>KafkaClient: Timeout
    
    Note over KafkaClient: Wait 200ms (exponential backoff)
    KafkaClient->>Broker: Retry 2
    Broker-->>KafkaClient: Timeout
    
    Note over KafkaClient: Wait 400ms
    KafkaClient->>Broker: Retry 3
    Broker-->>KafkaClient: Timeout
    
    Note over KafkaClient: Total time: ~10 seconds<br/>Application timeout reached
    
    KafkaClient-->>-Service: TimeoutException
    Service->>Service: Log failed order to failed-orders.log
    Service-->>-API: ServiceUnavailableException
    API-->>-Client: HTTP 500 Internal Server Error

    Note over Client: Client knows order failed<br/>No "ghost success" in Kafka
```

**Exponential Backoff Schedule:**
```
Attempt 1:  0ms    (immediate)
Retry 1:    100ms
Retry 2:    200ms  (100 * 2^1)
Retry 3:    400ms  (100 * 2^2)
Retry 4:    800ms
Retry 5:    1.6s
Retry 6:    3.2s
(continues until 8s delivery timeout)

Total retry window: ~8 seconds
Application timeout: 10 seconds (prevents ghost successes)
Max delivery timeout: 8 seconds (Kafka stops before API timeout)
```

**Why This Works:**
- Delivery timeout (8s) < Application timeout (10s)
- If application timeout fires, Kafka client has already stopped retrying
- No message arrives in Kafka after client receives error response
- Guarantees client knows the outcome: either success or definitive failure

---
### Level 2: Circuit Breaker (Fail-Fast Mechanism)

**Implementation**: Resilience4j Circuit Breaker

- **Action**: Protected by **Resilience4j**, the system monitors the failure rate of Kafka calls.
- **Tripping the Circuit**: If **50% of calls fail** within a sliding window of 10 attempts, the circuit opens for **30 seconds**.
- **Behavior**: When the circuit is **OPEN**, the system immediately rejects new Kafka calls without attempting them, protecting application threads from exhaustion.
- **API Response**: Returns **503 Service Unavailable**. 
- **Architectural Reasoning**: 503 indicates a temporary state where the service is protecting itself.

**Circuit Breaker States:**

```mermaid
stateDiagram-v2
    [*] --> CLOSED: Initial State
    CLOSED --> OPEN: Failure Rate >= 50%<br/>(10 calls in window)
    OPEN --> HALF_OPEN: After 30 seconds<br/>(wait duration)
    HALF_OPEN --> CLOSED: Test calls succeed<br/>(2/3 pass)
    HALF_OPEN --> OPEN: Test calls fail<br/>(2/3 fail)
    CLOSED --> CLOSED: Success Rate OK
    
    note right of CLOSED
        Normal Operation
        All requests attempted
        Monitoring failure rate
    end note
    
    note right of OPEN
        Fail-Fast Mode
        Immediate rejection
        No Kafka calls attempted
        Returns 503 immediately
    end note
    
    note right of HALF_OPEN
        Testing Recovery
        Limited requests allowed
        Evaluates broker health
    end note
```

**Circuit Breaker Flow:**

```mermaid
sequenceDiagram
    participant Client1 as Client Request 1
    participant Client2 as Client Request 2
    participant CB as Circuit Breaker
    participant Kafka as KafkaProducerService
    participant Broker as Kafka Broker

    Note over CB: State: CLOSED (Normal)
    
    Client1->>+CB: create-order (Request 1)
    CB->>+Kafka: Forward to sendOrder()
    Kafka->>Broker: Attempt send
    Broker-->>Kafka: Timeout
    Kafka-->>-CB: Exception
    CB-->>-Client1: HTTP 500
    
    Note over CB: Failure count: 1/10
    
    Client2->>+CB: create-order (Request 2-5)
    CB->>Kafka: Forward
    Kafka->>Broker: Attempt send
    Broker-->>Kafka: Timeout
    Kafka-->>CB: Exception
    CB-->>-Client2: HTTP 500
    
    Note over CB: Failure count: 5/10<br/>Failure rate: 50%<br/>Circuit OPENS
    
    Note over CB: State: OPEN<br/>(Kafka calls blocked)
    
    Client1->>+CB: create-order (Request 6)
    CB->>CB: Circuit is OPEN
    CB-->>-Client1: HTTP 503 Service Unavailable<br/>(Circuit Breaker Open)
    
    Note over CB: No Kafka call attempted!<br/>Immediate fast-fail
    
    Note over CB: Wait 30 seconds...
    
    Note over CB: State: HALF_OPEN<br/>(Testing recovery)
    
    Client2->>+CB: create-order (Test Request)
    CB->>+Kafka: Forward (limited test)
    Kafka->>Broker: Attempt send
    Broker-->>Kafka: Success ACK
    Kafka-->>-CB: Success
    CB-->>-Client2: HTTP 201 Created
    
    Note over CB: Test successful<br/>Circuit CLOSES
    
    Note over CB: State: CLOSED<br/>(Normal operation resumed)
```

**Configuration:**
```properties
resilience4j.circuitbreaker.instances.cartService.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.cartService.sliding-window-size=10
resilience4j.circuitbreaker.instances.cartService.wait-duration-in-open-state=30s
resilience4j.circuitbreaker.instances.cartService.permitted-number-of-calls-in-half-open-state=3
resilience4j.circuitbreaker.instances.cartService.sliding-window-type=COUNT_BASED
```

**Benefits:**
- ✅ Protects application threads from hanging on unavailable Kafka broker
- ✅ Fast-fail reduces client wait time (immediate 503 vs 10s timeout)
- ✅ Automatic recovery testing (half-open state)
- ✅ Prevents cascade failures to upstream services

---
### Level 3: Data Safety (Manual Recovery Fallback)
- **Action**: If the timeout is reached OR the Circuit Breaker is open, the system logs the **full order details** to a dedicated `failed-orders.log` file.
- **Implementation**: A dedicated logger (`FAILED_ORDERS_LOGGER`) captures the order payload and failure reason.
- **Goal**: Ensure no customer data is lost. This allows an administrator to re-process the orders once the Kafka cluster is restored.

---

### Topic Not Found Error (TOPIC_NOT_FOUND)

**Scenario**: The configured Kafka topic does not exist and auto-topic creation is disabled.

**Detection**:
- The `KafkaConnectivityService` uses a **two-pass approach** to accurately distinguish between Kafka being down vs. topic not found
- This detection happens in two places:
  1. **Health Check**: Background monitoring continuously checks topic existence
  2. **Producer Send**: When sending a message fails due to missing topic

**Implementation** (Two-Pass Detection):
```java
// KafkaConnectivityService.java
public boolean isTopicNotFoundException(Throwable e) {
    // FIRST PASS: Rule out connection/timeout issues (these are NOT topic issues)
    Throwable cause = e;
    while (cause != null) {
        // Connection/timeout exceptions indicate Kafka is down, not a topic issue
        if (cause instanceof java.util.concurrent.TimeoutException ||
            cause instanceof java.io.IOException ||
            cause instanceof org.apache.kafka.common.errors.TimeoutException) {
            String message = cause.getMessage();
            // If message indicates connection/timeout, this is NOT a topic issue
            if (message != null && (
                message.contains("timed out") ||
                message.contains("Connection refused") ||
                message.contains("Failed to update metadata") ||
                message.contains("broker") ||
                message.contains("not available"))) {
                return false;  // This is KAFKA_DOWN, not TOPIC_NOT_FOUND
            }
        }
        cause = cause.getCause();
    }
    
    // SECOND PASS: Check for actual topic-not-found exceptions
    cause = e;
    while (cause != null) {
        if (cause instanceof UnknownTopicOrPartitionException) {
            return true;
        }
        // Only check for very specific topic-related error messages
        String message = cause.getMessage();
        if (message != null && message.contains("UnknownTopicOrPartition")) {
            return true;
        }
        cause = cause.getCause();
    }
    return false;
}
```

**Why Two-Pass Approach?**
- **Problem**: Metadata-related errors can occur when either Kafka is down OR topic doesn't exist
- **Solution**: First eliminate connection issues, then check for topic-specific problems
- **Result**: Accurate error type reporting (KAFKA_DOWN vs TOPIC_NOT_FOUND)

**Health Check Integration**:
The `/health/ready` endpoint will return:
```json
{
  "name": "Producer (Cart Service)",
  "type": "readiness",
  "status": "DOWN",
  "timestamp": "2026-01-03T12:34:56.789Z",
  "checks": {
    "service": {
      "status": "UP",
      "details": "Cart Service is running and responsive"
    },
    "kafka": {
      "status": "DOWN",
      "details": "Topic 'orders' does not exist"
    }
  }
}
```
HTTP Status: **503 Service Unavailable**

**API Response (during order creation/update)**:
- **Status**: `500 Internal Server Error`
- **Error Type**: `TOPIC_NOT_FOUND`
- **Response Body**:
```json
{
  "timestamp": "2026-01-03T12:34:56.789Z",
  "error": "Internal Server Error",
  "message": "The configured Kafka topic does not exist.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "TOPIC_NOT_FOUND",
    "orderId": "ORD-ABC123",
    "topicName": "orders"
  }
}
```

**Architectural Reasoning**:
- This is a **configuration error** that prevents message delivery
- Returns 500 because it's a server-side misconfiguration, not a temporary outage
- **Different from**:
  - `KAFKA_DOWN`: Broker is unreachable (connection/timeout errors) → temporary infrastructure issue
  - `TOPIC_NOT_FOUND`: Broker is reachable but topic doesn't exist → configuration issue
  - `CIRCUIT_BREAKER_OPEN`: Temporary protection mechanism → resilience pattern
- The **two-pass detection** ensures accurate classification:
  1. First checks if it's a connection issue (KAFKA_DOWN)
  2. Only then checks if it's a topic issue (TOPIC_NOT_FOUND)
- Detected by `KafkaConnectivityService` which continuously monitors topic availability

**Resolution**:
1. Create the missing topic manually:
   ```bash
   docker exec kafka kafka-topics --bootstrap-server localhost:9092 \
     --create --topic orders --partitions 3 --replication-factor 1
   ```
2. The `KafkaConnectivityService` will automatically detect the topic once created and update the health status

**Monitoring**:
- The service continuously monitors topic availability with exponential backoff
- Health endpoints reflect real-time topic status
- Check `/cart-service/health/ready` to verify topic is available

**Testing**:
Use the provided test script: `./scripts/test-missing-topic.sh`

---

## 2. Resiliency & Consistency Patterns

To ensure the Producer remains a "source of truth," we implement advanced patterns in the `OrderService`.

### Local Store Consistency (Save-with-Rollback)
- **Responsiveness**: We use a 10s synchronous API timeout (`producer.send.timeout.ms`) to ensure the application remains responsive even during broker outages.
- **Rollback Logic**: If Kafka fails after the timeout or due to an open circuit, we revert the in-memory `orderStore`:
    - **Create**: The failed order is **removed** from the store.
    - **Update**: The **previous version** of the order is restored.
- **Consistency Guarantee**: This ensures the local state doesn't "lie" to the user. If the client receives a 500 or 503 error, they can safely retry the request knowing the system state has been reverted to its pre-failure condition.

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

## 5. Kafka Connection Error (KAFKA_DOWN)

**Scenario**: The Kafka broker is unreachable due to network issues, Kafka being stopped, or connection timeouts.

**Detection**:
- Connection/timeout exceptions: `TimeoutException`, `IOException`, connection refused errors
- The `KafkaConnectivityService` distinguishes this from `TOPIC_NOT_FOUND` using the two-pass approach
- Background monitoring continuously attempts reconnection with exponential backoff

**API Response (during order creation/update)**:
- **Status**: `500 Internal Server Error`
- **Error Type**: `KAFKA_DOWN`
- **Response Body**:
```json
{
  "timestamp": "2026-01-04T12:34:56.789Z",
  "error": "Internal Server Error",
  "message": "The server encountered an error while publishing the order event.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "KAFKA_DOWN",
    "orderId": "ORD-ABC123"
  }
}
```

**Health Check Response**:
The `/health/ready` endpoint will return:
```json
{
  "name": "Producer (Cart Service)",
  "type": "readiness",
  "status": "DOWN",
  "timestamp": "2026-01-04T12:34:56.789Z",
  "checks": {
    "service": {
      "status": "UP",
      "details": "Cart Service is running and responsive"
    },
    "kafka": {
      "status": "DOWN",
      "details": "Cannot connect to Kafka broker at kafka:29092"
    }
  }
}
```
HTTP Status: **503 Service Unavailable**

**Architectural Reasoning**:
- This is a **temporary infrastructure issue**, not a configuration error
- Returns **500 Internal Server Error** because the send operation failed unexpectedly during request processing
- Thrown as `ServiceUnavailableException` with type `KAFKA_DOWN`
- **Circuit Breaker Integration**: After repeated failures (50% failure rate), circuit breaker opens
  - Circuit open state throws `CallNotPermittedException` → returns **503 Service Unavailable** with type `CIRCUIT_BREAKER_OPEN`
- **Auto-Recovery**: Background monitoring continuously retries with exponential backoff
- Once Kafka is restored, service automatically recovers without restart

**Resolution**:
1. Check Kafka service status:
   ```bash
   docker-compose ps kafka
   docker-compose logs kafka
   ```
2. Restart Kafka if needed:
   ```bash
   docker-compose up -d kafka
   ```
3. The `KafkaConnectivityService` will automatically detect when Kafka is back and update health status
4. Circuit breaker will close once successful sends resume

**Monitoring**:
- Check `/cart-service/health/ready` to verify Kafka connectivity
- Watch for circuit breaker state (OPEN → HALF_OPEN → CLOSED)
- Background monitoring provides automatic recovery

---

## 6. Validation & Syntax Errors

Represent malformed data from the client.

- **Action**: Handled by Spring Bean Validation and the `GlobalExceptionHandler`.
- **Response**: Returns **400 Bad Request** with field-level details (e.g., "numItems is required").
- **Recovery**: The system rejects the request immediately to prevent "poison pill" messages from entering the Kafka stream.

---

## 7. System Health & Proactivity

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

### 500 Internal Server Error (Kafka Down)
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

### 500 Internal Server Error (Topic Not Found)
```json
{
  "timestamp": "2026-01-01T12:00:00.000Z",
  "error": "Internal Server Error",
  "message": "The configured Kafka topic does not exist and auto-creation is disabled.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "TOPIC_NOT_FOUND",
    "orderId": "ORD-000A",
    "topicName": "orders"
  }
}
```

### 503 Service Unavailable (Circuit Breaker Open)
```json
{
  "timestamp": "2026-01-01T12:00:00.000Z",
  "error": "Service Unavailable",
  "message": "The service is temporarily unavailable due to high failure rates. Please try again later.",
  "path": "/cart-service/create-order",
  "details": {
    "type": "CIRCUIT_BREAKER_OPEN"
  }
}
```
