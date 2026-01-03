# PLAN.md: Consumer Service (Order Service) Implementation Plan

## Project Overview
The **Order Service** acts as the consumer in our event-driven system. It is responsible for:
* Listening to order events from Kafka
* Maintaining local state of all processed orders
* Providing REST API access to query order status

---

## ✅ Phase 1: Kafka Consumer Configuration
Basic Kafka consumer setup with manual acknowledgment for "At-Least-Once" delivery.

- [x] Configure Kafka bootstrap servers
- [x] Configure consumer group ID
- [x] Set auto-offset-reset to `earliest`
- [x] Configure StringDeserializers
- [x] Hard-code topic name in docker-compose.yml
- [x] Set manual offset management (`enable.auto.commit = false`)
- [x] Configure MANUAL_IMMEDIATE acknowledgment mode

**See:** [CONFIG.md](CONFIG.md) for detailed configuration options

---

## ✅ Phase 2: Core Event Processing Logic (State Mirroring)
Implement the @KafkaListener to process order events with idempotency, sequencing validation, and shipping cost calculation.

- [x] Implement @KafkaListener for topic subscription
- [x] Implement JSON deserialization with ObjectMapper
- [x] Implement idempotency check (duplicate detection)
- [x] Implement sequencing validation (state machine)
- [x] Calculate shipping cost based on order items
- [x] Create ProcessedOrder record (Order + shipping cost)
- [x] Implement manual acknowledgment (MANUAL_IMMEDIATE)
- [x] Handle poison pill errors (malformed JSON)
- [x] Prevent status field modification

**Message Processing Workflow:**
1. Receive & Deserialize JSON → Order object
2. Validate message key (should match orderId)
3. Check Idempotency → Skip if exact duplicate
4. Check Sequencing → Reject if invalid transition
5. Calculate Shipping Cost → Business logic (Exercise 1)
6. Update Local State → Save ProcessedOrder
7. Acknowledge → Commit offset to Kafka

**Status Transitions Allowed:**
```
CREATED → CONFIRMED → DISPATCHED → DELIVERED
```

**See:** [ERRORS.md](ERRORS.md) for error handling details

---

## ✅ Phase 3: API Endpoint Implementation
REST API endpoints for order operations and health checks.

- [x] Implement root endpoint (GET /)
- [x] Implement liveness probe (GET /health/live)
- [x] Implement readiness probe (GET /health/ready)
- [x] Implement `POST /order-details` endpoint with orderId validation
- [x] Implement `POST /getAllOrdersFromTopic` endpoint
- [x] Create HealthService for health check operations
- [x] Add 404 error handling for missing orders
- [x] Add 503 Service Unavailable for broker outages

**Endpoints Implemented:**
- `GET /order-service/` → Root metadata with structured endpoints
- `GET /order-service/health/live` → Liveness probe (service check)
- `GET /order-service/health/ready` → Readiness probe (service + Kafka + state checks)
- `POST /order-service/order-details` → Get order details with shipping cost
- `POST /order-service/getAllOrdersFromTopic` → Get all order IDs from topic

**HealthService Methods:**
- `getServiceStatus()` → Returns service health (always UP if callable)
- `getKafkaStatus()` → Uses Kafka AdminClient to verify broker connectivity (3s timeout)
- `getLocalStateStatus()` → Returns local state store accessibility status

**See:** [CONFIG.md](CONFIG.md) for detailed endpoint configurations

---

## ⏳ Phase 4: Resilience & Error Handling
Implement robust error handling for production readiness.

- [x] Implement HealthService for broker monitoring
- [x] Add 503 Service Unavailable response for broker outages
- [x] Implement poison pill handling (malformed JSON)
- [x] Add comprehensive logging at key operations
- [ ] Test graceful shutdown behavior
- [ ] Add circuit breaker pattern (optional)

**See:** [ERRORS.md](ERRORS.md) for comprehensive error handling strategies

---

## ✅ Phase 5: Docker Orchestration
Set up lightweight Docker Compose configuration and multi-stage Dockerfile.

- [x] Create lightweight docker-compose.yml (consumer only)
- [x] Create multi-stage Dockerfile (Maven builder + JRE runtime)
- [x] Configure autoStartup=false for standalone mode support
- [x] Set hard-coded environment variables
- [x] Configure health checks with start_period
- [x] Align producer and consumer docker-compose files
- [x] Configure logging in both services
- [x] Enable Consumer to run with or without Kafka

**Architecture:**
```
Producer (docker-compose)  ← Manages: Kafka, Zookeeper, Producer
                             Network: producer_ecommerce-network
                             Port: 8081
                             
Consumer (docker-compose)  ← Manages: Consumer only
                             Network: Standalone or producer_ecommerce-network
                             Port: 8082
                             Mode: Standalone OR Integrated
```

**Running the Stack:**

**Standalone Mode (Consumer without Kafka):**
```bash
cd consumer && docker compose up
# Consumer runs on port 8082, Kafka health shows DOWN
```

**Integrated Mode (Consumer with Producer's Kafka):**
```bash
# Step 1: Start Producer
cd producer && docker compose up -d

# Step 2: Start Consumer
cd consumer && docker compose up -d

# Step 3: Connect Consumer to Producer's network
docker network connect producer_ecommerce-network order-service

# Step 4: Restart Consumer
docker restart order-service
```

**Key Configuration:**
- `autoStartup=false` in KafkaConsumerConfig allows standalone operation
- HealthService uses AdminClient for actual Kafka connectivity verification
- Consumer starts successfully even when Kafka is unavailable

**See:** [CONFIG.md](CONFIG.md) for environment variables and configuration details

---

## 📁 Documentation Structure

| File | Purpose |
|------|---------|
| **PLAN.md** | Overview, phases, and progress tracking (this file) |
| **CONFIG.md** | Detailed configuration options and rationale |
| **ERRORS.md** | Error handling mechanisms and strategies |
| **STRUCTURE.md** | Project folder structure and file organization |

---

## Educational Justification (MTA EDA Standards)

- **Manual Offsets:** "At-Least-Once" delivery guarantee. Messages only acknowledged after state update.
- **Message Keying:** Using `orderId` as key ensures sequencing within partitions.
- **Idempotency:** Handles duplicate delivery from "At-Least-Once" semantics.
- **State Machine:** Prevents out-of-order events from corrupting state.
- **Separation of Concerns:** Each .md file focuses on one aspect (config, errors, structure).
