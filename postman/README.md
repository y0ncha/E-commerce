# Postman Test Suite - Exercise 2 (Kafka)

## Overview

This directory contains automated Postman test collections for **Exercise 2** of the EDA (Event-Driven Architecture) course. The test suites validate Kafka-based resilience patterns, event sequencing guarantees, and data quality controls in the E-commerce microservices system.

## Test Collections

### 1. Ex02-Resilience-and-Idempotency.postman_collection.json
**Focus:** Resilience patterns and idempotent message processing

**Test Coverage:**
- ✅ **Health Checks:** Verify Producer and Consumer services with Kafka connectivity
- ✅ **Idempotency - Create:** Duplicate `orderId` handling (409 Conflict from Producer)
- ✅ **Idempotency - Update:** Duplicate status update handling (Consumer skips exact duplicates)
- ✅ **Error Handling:** Non-existent orderId queries (404 Not Found)
- ✅ **Diagnostic:** Verify order presence in Kafka topic

**Key Scenarios:**
1. Create order → Verify Consumer processing
2. Attempt duplicate create → Producer rejects with 409
3. Update order status → Verify Consumer updates state
4. Send duplicate update → Consumer handles idempotently (At-Least-Once semantics)
5. Query non-existent order → 404 Not Found

**Educational Justification:**
- **At-Least-Once Delivery:** Kafka may deliver the same message multiple times. Consumers must handle duplicates gracefully.
- **Idempotency Check:** Consumer compares incoming `Order` with stored state using `.equals()` to skip exact duplicates.
- **Message Key:** Using `orderId` as the Kafka message key ensures all events for the same order are processed in order.

---

### 2. Ex02-Sequencing.postman_collection.json
**Focus:** Event ordering and state machine validation

**Test Coverage:**
- ✅ **Valid Transitions:** `CREATED → CONFIRMED → DISPATCHED → DELIVERED`
- ✅ **Invalid Transitions:** Backward transitions (e.g., `DISPATCHED → CREATED`)
- ✅ **Terminal State:** No transitions allowed from `DELIVERED`
- ✅ **Fast-Forward:** Skip intermediate states (e.g., `CREATED → DELIVERED`)
- ✅ **State Preservation:** Consumer rejects invalid transitions without corrupting state

**State Machine Rules:**
```
CREATED     → CONFIRMED, DISPATCHED, DELIVERED  ✅
CONFIRMED   → DISPATCHED, DELIVERED             ✅
DISPATCHED  → DELIVERED                         ✅
DELIVERED   → [Terminal - No transitions]       ❌
```

**Key Scenarios:**
1. Create order with status `CREATED`
2. Valid progression through all states
3. Attempt backward transition → Consumer rejects, state preserved
4. Attempt transition from terminal state → Consumer rejects
5. Fast-forward test → Skip intermediate states (allowed)

**Educational Justification:**
- **Kafka Ordering Guarantee:** Events with the same key (orderId) are processed sequentially within a partition.
- **State Machine Validation:** Consumer enforces business logic to prevent out-of-order events from corrupting state.
- **Defensive Programming:** Unknown future statuses default to "allow" for extensibility.

---

### 3. Ex02-Validation.postman_collection.json
**Focus:** Input validation and data quality

**Test Coverage:**

**Producer Validation:**
- ✅ Missing required fields (`orderId`, `numItems`)
- ✅ Empty/blank values
- ✅ Invalid orderId format (non-hexadecimal characters)
- ✅ Invalid field types (string instead of integer)
- ✅ Malformed JSON
- ✅ Extra/unknown fields (gracefully ignored)

**Consumer Validation:**
- ✅ Missing required fields (`orderId`, `topicName`)
- ✅ Empty/blank values
- ✅ Invalid orderId format (non-hex characters)
- ✅ Malformed JSON

**Boundary Conditions:**
- ✅ Zero `numItems`
- ✅ Negative `numItems`
- ✅ Very long `orderId` (1000 characters)

**Expected Responses:**
- **400 Bad Request:** Validation errors with descriptive messages
- **404 Not Found:** Order not found in Consumer state
- **409 Conflict:** Duplicate orderId in Producer

**Educational Justification:**
- **Fail-Fast Validation:** Use Jakarta Bean Validation (`@NotBlank`, `@Pattern`) to reject invalid requests early.
- **Consistent Error Responses:** `GlobalExceptionHandler` provides uniform error structure across all endpoints.
- **Poison Pill Handling:** Consumer logs and acknowledges malformed JSON to avoid infinite retry loops.

---

## Prerequisites

### Required Services
1. **Kafka Broker** (running on `localhost:9092`)
2. **Producer Service** (Cart Service on `localhost:8081`)
3. **Consumer Service** (Order Service on `localhost:8082`)

### Environment Setup

**Option 1: Import Environment (Recommended)**
1. Import `environments/EDA-Local.postman_environment.json`
2. Environment variables:
   - `base_url_producer`: `http://localhost:8081/cart-service`
   - `base_url_consumer`: `http://localhost:8082/order-service`
   - `order_id`: (auto-generated per test)

**Option 2: Manual Configuration**
Create a new environment in Postman with the variables above.

---

## Running the Tests

### Option 1: Individual Collection
1. Open Postman
2. Import the desired collection from `postman/collections/`
3. Select the `EDA-Local` environment
4. Click "Run Collection" → "Run Ex02-..."
5. View results and assertions

### Option 2: Collection Runner (All Tests)
1. Import all three collections
2. Select `EDA-Local` environment
3. Use Collection Runner to execute in sequence:
   - Ex02-Validation (data quality baseline)
   - Ex02-Resilience-and-Idempotency (operational resilience)
   - Ex02-Sequencing (state machine validation)

### Option 3: Newman CLI (Automated)
```bash
# Install Newman (Postman CLI)
npm install -g newman

# Run individual collection
newman run postman/collections/Ex02-Resilience-and-Idempotency.postman_collection.json \
  -e postman/environments/EDA-Local.postman_environment.json

# Run all collections
newman run postman/collections/Ex02-Validation.postman_collection.json \
  -e postman/environments/EDA-Local.postman_environment.json

newman run postman/collections/Ex02-Resilience-and-Idempotency.postman_collection.json \
  -e postman/environments/EDA-Local.postman_environment.json

newman run postman/collections/Ex02-Sequencing.postman_collection.json \
  -e postman/environments/EDA-Local.postman_environment.json
```

---

## Test Design Principles

### 1. Atomic Tests (One Scenario Per Test)
Each test request validates a single behavior. This ensures:
- **Clear Failure Diagnosis:** Failed tests pinpoint exact issues
- **Independent Execution:** Tests don't depend on each other (except within a collection)
- **Educational Clarity:** Each test demonstrates one Kafka/validation concept

### 2. Pre-Request Scripts
- Generate unique `orderId` values to avoid cross-test interference
- Hex format: 16-character hexadecimal strings (e.g., `"ABC123DEF4567890"`)

### 3. Test Assertions
- **Status Code Validation:** Verify HTTP response codes (200, 201, 400, 404, 409)
- **Response Body Validation:** Check JSON structure and key fields
- **State Preservation:** Verify Consumer state after invalid transitions

### 4. Educational Comments
- Test descriptions cite course concepts (e.g., "At-Least-Once delivery")
- Console logs explain behavior for learning purposes

---

## Expected Failures & Scenarios

### Resilience Collection
| Test | Expected Result | Reason |
|------|----------------|--------|
| Duplicate Create Order | 409 Conflict | Producer rejects duplicate `orderId` |
| Duplicate Update | 200 OK | Producer publishes; Consumer skips idempotently |
| Non-Existent Order | 404 Not Found | Order not in Consumer state |

### Sequencing Collection
| Test | Expected Result | Reason |
|------|----------------|--------|
| Valid Transitions | 200 OK | State machine allows forward progress |
| Backward Transitions | State Preserved | Consumer rejects; status unchanged |
| Terminal Transition | State Preserved | No transitions from `DELIVERED` |

### Validation Collection
| Test | Expected Result | Reason |
|------|----------------|--------|
| Missing Fields | 400 Bad Request | Bean Validation (`@NotBlank`) |
| Invalid Format | 400 Bad Request | Pattern validation (`@Pattern`) |
| Malformed JSON | 400 Bad Request | `HttpMessageNotReadableException` |
| Extra Fields | 201 Created | Spring Boot ignores unknown fields |

---

## Extending the Test Suite

### Adding New Test Cases

**1. Create a New Request in Existing Collection:**
```json
{
  "name": "Test X.X - Your Scenario Description",
  "request": {
    "method": "POST|PUT|GET",
    "url": "{{base_url_producer}}/endpoint",
    "body": { ... }
  },
  "event": [
    {
      "listen": "test",
      "script": {
        "exec": [
          "pm.test('Assertion description', () => {",
          "    pm.response.to.have.status(200);",
          "});"
        ]
      }
    }
  ]
}
```

**2. Create a New Collection:**
- Use `Ex02-*.postman_collection.json` as templates
- Follow naming convention: `Ex02-[Feature].postman_collection.json`
- Update `info.description` with test coverage details

**3. Test Naming Convention:**
```
Test [Phase].[Step] - [Action]: [Expected Behavior]
```
Examples:
- `Test 1.1 - Create Order (First Time)`
- `Test 4.2 - Verify Consumer Rejected Backward Transition`

---

## Troubleshooting

### Services Not Running
**Error:** Connection refused on `localhost:8081` or `localhost:8082`

**Solution:**
```bash
# Start Producer (includes Kafka and Zookeeper)
cd producer && docker-compose up -d

# Start Consumer
cd consumer && docker-compose up -d

# Verify services
curl http://localhost:8081/cart-service/health/ready
curl http://localhost:8082/order-service/health/ready
```

### Kafka Broker Unavailable
**Error:** `checks.kafka.status: "DOWN"` in health check

**Solution:**
```bash
# Check Kafka container
docker ps | grep kafka

# Restart Kafka
cd producer && docker-compose restart kafka

# Wait 30 seconds for broker to be ready
```

### Test Failures Due to Timing
**Issue:** Consumer hasn't processed message before query

**Solution:**
- Collections include `setTimeout(() => {}, 500)` delays
- Increase delay in test scripts if needed:
  ```javascript
  setTimeout(() => {}, 1000); // 1 second
  ```

### Stale Test Data
**Issue:** Previous test runs left orders in Consumer state

**Solution:**
```bash
# Restart Consumer to clear in-memory state
cd consumer && docker-compose restart order-service
```

---

## Integration with CI/CD

### GitHub Actions Example
```yaml
name: Postman Tests

on: [push, pull_request]

jobs:
  api-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Start Services
        run: |
          cd producer && docker-compose up -d
          cd ../consumer && docker-compose up -d
          sleep 30  # Wait for services to be ready
      
      - name: Install Newman
        run: npm install -g newman
      
      - name: Run Validation Tests
        run: newman run postman/collections/Ex02-Validation.postman_collection.json \
          -e postman/environments/EDA-Local.postman_environment.json
      
      - name: Run Resilience Tests
        run: newman run postman/collections/Ex02-Resilience-and-Idempotency.postman_collection.json \
          -e postman/environments/EDA-Local.postman_environment.json
      
      - name: Run Sequencing Tests
        run: newman run postman/collections/Ex02-Sequencing.postman_collection.json \
          -e postman/environments/EDA-Local.postman_environment.json
```

---

## Architecture Reference

### Message Flow
```
Client → Producer (Cart Service) → Kafka Topic (orders) → Consumer (Order Service)
```

### Key Kafka Concepts Tested
1. **Message Key:** `orderId` ensures partition-level ordering
2. **At-Least-Once Delivery:** Messages may be delivered multiple times
3. **Manual Offset Commits:** Consumer commits only after successful processing
4. **Idempotency:** Consumer handles duplicate messages gracefully

### API Endpoints

**Producer (Cart Service):**
- `POST /cart-service/create-order` - Create new order
- `PUT /cart-service/update-order` - Update order status
- `GET /cart-service/health/ready` - Readiness probe

**Consumer (Order Service):**
- `POST /order-service/order-details` - Get order with shipping cost
- `POST /order-service/getAllOrdersFromTopic` - List all processed orders
- `GET /order-service/health/ready` - Readiness probe

---

## Additional Resources

- **Course Material:** Review Session 6 (Kafka Consumer) and Session 7 (Error Handling)
- **Exercise Requirements:** See `../docs/Ex.02.md`
- **Service Plans:**
  - Producer: `../producer/docs/PLAN.md`
  - Consumer: `../consumer/docs/PLAN.md`
- **Error Handling:** `../consumer/docs/ERRORS.md`

---

## TL;DR

**Quick Start:**
1. Start services: `cd producer && docker-compose up -d && cd ../consumer && docker-compose up -d`
2. Import environment: `postman/environments/EDA-Local.postman_environment.json`
3. Run collections in Postman or via Newman CLI
4. Review test assertions and console logs for educational insights

**Test Coverage:**
- **Resilience:** Idempotency, error handling, diagnostic checks
- **Sequencing:** State machine validation, ordering guarantees
- **Validation:** Input validation, boundary conditions, malformed data

**Key Takeaway:**
These tests validate that the system correctly implements Kafka-specific patterns (message keying, ordering, manual commits) and handles edge cases gracefully.
