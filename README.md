# README - E-commerce Platform Exercise 2

Yonatan Csasznik - ID: 208259077

---

## 1. Quick Start

The system must be started in the correct order because the consumer connects to the **producer's Docker network**.

---

**Option 1: Automated Startup (Recommended)**
```bash
run.bat
# ...
stop.bat
```

**Option 2: Manual Startup**
```bash
# Terminal 1 - Start Producer (creates network & Kafka)
cd producer
docker compose up -d

# Terminal 2 - Start Consumer (joins producer's network after 15 seconds)
cd consumer
docker compose up -d
```

### Verification
```bash
# Producer Health
curl http://localhost:8081/cart-service/health/live

# Consumer Health
curl http://localhost:8082/order-service/health/live

# Kafka UI
open http://localhost:8080
```

---

## 2. Topic Configuration & Purpose

I use two Kafka topics to maintain high availability and ensure strict message ordering as required by the Exercise 2 specifications:

### 1. orders

**Producer**: Cart Service.

**Consumer**: Order Service.

**Purpose**: This is the primary event stream for the order lifecycle (e.g. CREATED, UPDATED).

**Design Choice (Single Topic)**: I use a single `orders` topic for all order lifecycle events rather than splitting into multiple topics (e.g., `orders-created`, `orders-updated`), separate topics would break ordering guarantees across independent partitions, causing race conditions and data integrity violations when events arrive out of sequence. 

### 2. orders-dlt (Dead Letter Topic)

**Producer**: Order Service.

**Consumer**: Monitoring Tools / Manual Intervention.

**Purpose**: To handle "Poison Pills" or unrecoverable processing errors (e.g., malformed JSON or persistent database timeouts).

**Design Choice (Resilience)**: By moving failed messages here after exhausting retries, I prevent the main orders partition from stalling (blocking). Each message includes metadata headers (error reason, original offset) to facilitate forensic analysis and eventual manual recovery, ensuring Zero Data Loss.

---

## 3. Partition Key

I employ `orderId` as the partition key to maintain strict ordering semantics per order and prevent race conditions in distributed processing.

### Partition Key: `orderId`

* **Justification**: Kafka hashes the message key to determine the partition. By using orderId, I guarantee that every event for a specific order is routed to the same partition.
* **Parallelism**: This strategy allows for high throughput as different orders can be processed in parallel across multiple partitions while maintaining internal consistency for each individual order.

---

## 4. Error Handling Strategies & Justifications

This system implements a comprehensive error handling framework designed to ensure **At-Least-Once** delivery, maintain data integrity, and provide resilience against infrastructure failure.

---

### I. Producer-Side (Cart Service)

**Idempotent Producer Configuration**:
* **Mechanism**: Kafka producer is configured with idempotence enabled, which automatically sets acknowledgments to require all replicas, enables high retry counts, and limits in-flight requests to maintain ordering.
* **Justification**: Prevents duplicate messages from being written to Kafka when the producer retries due to network errors or broker failures. This ensures that each event appears in the Kafka topic exactly once, even with retries. Combined with the consumer's manual offset management and dual-layer idempotency, this provides end-to-end **At-Least-Once** delivery with exactly-once side effects.

**Indefinite Retries & Delivery Timeouts**:
* **Mechanism**: Configured to require acknowledgment from all replicas with high retry counts (effectively indefinite), using exponential backoff.
* **Justification**: Requiring all-replica acknowledgment ensures maximum durability through cluster replication. Indefinite retries with exponential backoff allow the system to absorb transient network jitters or broker leader re-elections without failing the user request.

**Timeout Alignment (Ghost Success Prevention)**:
* **Mechanism**: The Kafka delivery timeout is set to be shorter than the overall API response timeout.
* **Justification**: This prevents a "ghost success" where an order appears in the system after the user was told the operation failed.

**Circuit Breaker (Fail-Fast Pattern)**:
* **Mechanism**: A stateful monitor that "opens" and halts requests to the broker when a failure threshold (50% failure rate over a sliding window of 10 calls) is reached, waiting 30 seconds before attempting recovery.
* **Justification**: During a total broker outage, the circuit breaker prevents the application from exhausting its own threads and resources by waiting for timeouts. It provides the infrastructure time to recover and returns an immediate 503 (Service Unavailable) to the client.

**Local State Rollback**:
* **Mechanism**: If the event cannot be published to Kafka, the service reverts the local in-memory state (e.g., deleting a newly created order or reverting a status update).
* **Justification**: This maintains strict consistency between the service's internal state and the persistent event log in Kafka.

**Request & Data Validation (Gatekeeper)**:
* **Mechanism**: Validate incoming REST requests for structural validity (malformed JSON, required fields) and semantic correctness before any Kafka operations. Enforce business constraints (totalAmount > 0, non-empty items, valid ISO 4217 currency codes, no duplicates) at the API entry point. For status updates, enforce that new orders start in the `NEW` state and that transitions follow the sequential state machine progression (e.g., `NEW` → `CONFIRMED` → `DISPATCHED`), rejecting non-sequential transitions. Invalid requests immediately fail with appropriate HTTP error codes (400 for malformed JSON, 422 for business logic violations).
* **Justification**: Prevents malformed or semantically invalid orders from ever reaching Kafka, eliminating wasted broker resources and consumer processing cycles. Fast failure to the client provides immediate feedback for correction and retry. By catching data quality and state machine issues at the producer source, the system reduces downstream burden, maintains data integrity, and prevents invalid state sequences from polluting the event stream.

---

### II. Consumer-Side (Order Service)

**Manual Offset Management**:
* **Mechanism**: Automatic offset commits are disabled. Offsets are committed manually only after the business logic (shipping calculation) has successfully completed.
* **Justification**: This is the core requirement for **At-Least-Once** delivery. If the consumer crashes during processing, the message will remain "unacknowledged" in Kafka and will be re-delivered to another consumer instance, ensuring no order is ever lost.

**Dual-Layer Idempotency**:

* **Infrastructure Layer**:
  * **Mechanism**: The Kafka Producer is configured with `enable.idempotence=true`.
  * **Justification**: This ensures that even if the producer retries a message due to a transient network error or "Ack Timeout," the broker will identify and discard the duplicate, maintaining a clean log at the transport level.

* **Business Logic Layer**:
  * **Mechanism**: Both the Producer and Consumer utilize a State Machine for deduplication.
  * **Justification**: This prevents logic-level duplication. The system verifies the current status of an `orderId` before applying any change (e.g., ignoring a CREATED event if the state is already CONFIRMED). This effectively handles "At-Least-Once" redeliveries, ensuring that side effects like shipping charges are never applied twice.

**Consumer Timeout Tuning**:
* **Mechanism**: Session timeout and heartbeat intervals are configured to prevent false-positive failure detection. Session timeout is set to 10 seconds (reduced from default for faster failure detection), with heartbeat interval at 2 seconds.
* **Justification**: If processing takes longer than the maximum poll interval, Kafka assumes the consumer has died and triggers a "rebalance," which causes significant lag. Proper tuning ensures stable consumption even during heavy processing tasks.

**Data Integrity & Business Rule Validation**:
* **Mechanism**: Validate that required fields (orderId, customerId, status, items, totalAmount) are present, non-null, and conform to expected data types. Enforce business constraints including totalAmount > 0, non-empty items list, valid currency codes (ISO 4217), and status values matching the defined state machine (NEW, CONFIRMED, DISPATCHED, COMPLETED, CANCELED). As a defense-in-depth measure, verify that status transitions follow the sequential state machine progression (e.g., `NEW` → `CONFIRMED`, `CONFIRMED` → `DISPATCHED`), rejecting out-of-order or non-sequential events.
* **Justification**: Provides comprehensive defense-in-depth by catching malformed events that pass JSON deserialization but violate structural, business logic, or state machine requirements. Acts as a protective barrier against schema evolution issues, producer bugs, data corruption, and out-of-order events, ensuring the final calculated shipping cost and order state remain logically consistent with the business workflow.

**Poison Pill Handling & DLT (Dead Letter Topic)**:
* **Mechanism**: Messages that fail processing (deserialization errors, validation failures, business logic exceptions) are automatically retried with exponential backoff (1s, 2s, 4s). After 3 retry attempts, Spring Kafka's `DeadLetterPublishingRecoverer` sends them to `orders-dlt` with enriched metadata headers including original topic, partition, offset, exception class name, exception message, and stack trace. The offset is then committed to prevent infinite retry loops.
* **Justification**: Without this mechanism, a single malformed message (poison pill) would block the entire consumer pipeline indefinitely. The automatic retry with backoff handles transient errors (temporary network issues), while DLT routing ensures persistent errors (malformed JSON, schema mismatches) don't halt processing. Spring Kafka's standard metadata headers enable forensic investigation without manual log correlation.

**Graceful Listener Degradation**:
* **Mechanism**: Dynamically start and stop message listeners based on the detected health of downstream dependencies (such as the local state store or database connections).
* **Justification**: Protects system persistence by ensuring the consumer does not pull messages from the broker when it cannot successfully process or store them. This prevents unnecessary retries, DLT accumulation, and resource exhaustion during dependency failures.

---

### III. Infrastructure & Auto-Recovery

**Adaptive Connectivity Monitoring (Producer & Consumer)**:
* **Mechanism**: An infinite retry loop with exponential backoff is implemented at both the producer and consumer levels for broker connections. Connection idle timeouts and metadata refresh intervals are reduced from defaults (60s to 5s) for faster failure detection and reconnection. The consumer's session timeout is configured to 10 seconds, while the producer applies request timeouts with exponential backoff to maintain connectivity resilience.
* **Justification**: Enhances resilience by allowing both services to automatically resume operations as soon as the infrastructure recovers, eliminating the need for manual service restarts after a broker outage. This self-healing capability reduces operational overhead and minimizes downtime across the entire distributed system.

**Proactive Health Monitoring**:
* **Mechanism**: Both producer and consumer implement background health checks with 30-second intervals and expose readiness probes on the `/health/ready` endpoint (with 3-second timeout). The consumer uses Kafka connectivity checks and the producer validates broker connectivity through circuit breaker status.
* **Justification**: Allows load balancers to proactively remove unhealthy instances before they can serve user requests, preventing cascading failures. Aligns with cloud-native best practices for container orchestration (Kubernetes readiness probes).

**Error Classification: Transient vs. Persistent (Producer & Consumer)**:
* **Mechanism**: The system distinguishes between transient errors (connection/timeout issues like `KAFKA_DOWN`) and persistent errors (configuration/data errors like `TOPIC_NOT_FOUND` or deserialization failures). Transient errors trigger exponential backoff retries at the producer level and automatic reconnection at the consumer level. Persistent errors are immediately failed fast at the producer (HTTP 500/503) or routed to the DLT at the consumer without retry.
* **Justification**: Retrying transient errors allows the system to absorb temporary infrastructure disruptions (broker restarts, network hiccups) without user-facing failures. Persistent errors cannot be resolved by retrying—they require code fixes, schema corrections, or manual intervention. By classifying errors correctly, the system avoids wasted retry cycles on unrecoverable errors and instead focuses resources on investigating and fixing root causes through the DLT.

---

### IV. Observability & Recovery

**Critical Metrics Monitoring**:
* **Mechanism**: Track **Idempotency Skip Rates**, **Consumer Lag**, and **Circuit Breaker Status** in real-time using application metrics and monitoring dashboards.
* **Justification**: Provides visibility into system health and validates the At-Least-Once strategy. A high idempotency skip rate proves that duplicate events are being successfully caught, while consumer lag metrics indicate processing bottlenecks before they cause rebalances.

**Operational DLT Redrive Procedure**:
* **Mechanism**: Analyze failed messages in `orders-dlt`, remediate root causes (code fixes, schema corrections), and manually republish messages to the main `orders` topic after verification.
* **Justification**: Prevents "Retry Storms" for non-transient data errors while ensuring eventual consistency. This controlled recovery process maintains data integrity without risking cascading failures from automated retry mechanisms that cannot distinguish between transient and persistent failures.

---

## 5. Important Implementation Notes

### State Machine Validation

Both producer and consumer enforce a strict state machine that prevents invalid status transitions and out-of-order events.

**Valid State Progression**:
```
new → confirmed → dispatched → completed
                ↓
            canceled (terminal state - accessible from any state)
```

**Justification**: Provides defense-in-depth validation across producer and consumer. The producer validates before publishing, preventing invalid transitions from entering the stream. The consumer re-validates on consumption, catching any out-of-order events caused by network delays, clock skew, or producer bugs. This layered approach ensures business logic (shipping calculations, order lifecycle) operates only on logically consistent state transitions, preventing race conditions and maintaining system resilience even when individual layers fail.

---

### Idempotency & Duplicate Detection

Idempotency is enforced at multiple layers:
- **Infrastructure Layer**: Kafka offset tracking per `orderId` prevents reprocessing the same message twice
- **Business Layer**: State validation detects duplicate events (same `orderId` with same status) and skips processing
- **Producer Layer**: Idempotent producer configuration prevents duplicate writes to Kafka topic

This dual/triple-layer approach achieves "Exactly-Once" side effects despite "At-Least-Once" delivery.

---

### Health Check Design Pattern

The system uses two distinct health endpoints:
- `/health/live`: Liveness probe - returns UP if service is running
- `/health/ready`: Readiness probe - returns UP only if Kafka is accessible

**Why This Matters**: Container orchestration systems use readiness probes to control traffic routing. A service may be alive but not ready to serve requests if Kafka is down. This separation prevents load balancers from routing traffic to unhealthy instances.

---

### Local State Rollback on Failure

If Kafka publish fails, the producer removes the order from `orderStore`:
- Maintains consistency between API state and Kafka topic
- API users see the failure immediately and can retry
- No "ghost orders" that appear created but never reach Kafka

**Critical Path**: Create order → Update local state → Publish to Kafka → If failure, rollback local state → Return error to client

---

## 6. Running and Testing Notes

### Docker Compose Configuration

The system is split into two separate Docker Compose files for independent scalability:

**Producer Compose (`producer/docker-compose.yml`)**:
- **Cart Service**: The order creation API (port 8081). Users submit orders here. Built from the producer Dockerfile.
- **Kafka Broker**: Message queue that stores order events. All orders flow through this.
- **Zookeeper**: Manages Kafka coordination behind the scenes.

**Consumer Compose (`consumer/docker-compose.yml`)**:
- **Order Service**: Listens to order events and calculates shipping costs (port 8082). Built from the consumer Dockerfile.
- **Kafka Broker**: Same message queue as above (shared infrastructure).
- **Zookeeper**: Same coordination service as above (shared infrastructure).

Each service maintains its own independent data store, ensuring loose coupling and resilience.

---

### Kafka UI

A web-based interface for monitoring and managing Kafka is available at:

```
http://localhost:8080
```

**Features**:
- View all topics (`orders`, `orders-dlt`)
- Inspect messages in each topic
- Monitor consumer groups and lag
- View partition details and offsets
- Useful for debugging message flow and verifying error handling

---

### Cold Start Note

Java Spring Boot applications experience initial startup delays during the first deployment and might take a few seconds to stableize.

---

### Postman Collections

Two Postman collections are attached to the submission :

**Sanity.postman_collection.json**:
- Basic happy-path tests for order creation and retrieval
- Validates that the system works end-to-end under normal conditions
- Tests producer and consumer endpoints with valid data

**Error-Cases.postman_collection.json**:
- Tests resilience and error handling scenarios
- Includes tests for malformed requests and invalid orders
- Validates proper HTTP error responses (400, 422, 503)

---

## 7. Foundational Groundwork for Production

The current system is architected with production scalability in mind. While the exercise uses in-memory storage and single-broker Kafka, the codebase is prepared for enterprise-grade enhancements without major refactoring.

---

### DLT Automation & Redrive Logic

Messages routed to `orders-dlt` include enriched metadata headers automatically added by Spring Kafka's `DeadLetterPublishingRecoverer`:
- `kafka_dlt-original-topic`: Source topic for message origin tracking
- `kafka_dlt-original-partition`: Original partition number for sequencing verification
- `kafka_dlt-original-offset`: Original offset for message recovery
- `kafka_dlt-exception-fqcn`: Fully qualified class name of the exception
- `kafka_dlt-exception-message`: Specific error description for root cause analysis
- `kafka_dlt-exception-stacktrace`: Complete stack trace for debugging
- `kafka_dlt-original-timestamp`: Original message timestamp for audit trails

**Production Ready**: This metadata enables future automated redrive services to:
- Analyze failure patterns and categorize errors
- Automatically retry transient errors after infrastructure recovery
- Route persistent errors to human investigation workflows
- Implement intelligent redrive scheduling based on error type

No changes to DLT schema or producer logic would be required to add redrive automation.

---

### Transition to Persistent External Memory (Database)

Currently, both producer and consumer use in-memory data structures:
- Producer: `ConcurrentHashMap<String, Order>` for order state
- Consumer: `ConcurrentHashMap<String, ProcessedOrder>` for processed orders

The service abstraction layers are designed for easy database migration:
- `OrderService` encapsulates all order operations (get, create, update)
- `ProcessedOrder` model can be persisted to any SQL or NoSQL store

**Production Ready**: Replacing in-memory storage with PostgreSQL or other high-availability databases requires only:
1. Add Spring Data JPA repositories
2. Replace `ConcurrentHashMap` operations with repository method calls
3. Add transaction management for ACID guarantees
4. No changes to business logic, API contracts, or Kafka integration

The modular design ensures the event-driven architecture remains unchanged while gaining persistent state and horizontal scalability.

---

### Kafka Cluster Expansion

The system uses `orderId` as the Kafka message partition key. This design decision ensures:
- All events for a single order remain in the same partition
- Strict ordering per order is maintained
- Consumer state machine validation works correctly

**Production Ready**: This single-broker Kafka setup scales seamlessly to multi-broker clusters:
- Kafka automatically distributes partitions across brokers
- Partition key ensures consistent routing and ordering
- No application code changes required for cluster expansion
- Consumer rebalancing is handled transparently

The system is already optimized for horizontal scaling across Kafka brokers without architectural changes.

---

