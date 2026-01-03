# Postman Test Collections for Exercise 2

This directory contains automated test suites for verifying the resilience, sequencing, and at-least-once delivery standards of Exercise 2 (Kafka E-commerce Backend).

## Files

### Collections
- **`Sanity.postman_collection.json`** - End-to-end flow testing (producer + consumer)
- **`Consumer-Endpoints.postman_collection.json`** - **NEW** Comprehensive consumer endpoint tests with validation
- **`collections/Ex02-Sequencing.postman_collection.json`** - Sequencing guarantee tests
- **`collections/Ex02-Resilience-and-Idempotency.postman_collection.json`** - Resilience pattern tests
- **`collections/Ex02-Validation.postman_collection.json`** - Data validation tests

### Configuration
- **`EDA-Local.postman_environment.json`** - Environment configuration for local testing
- **`environments/EDA-Local.postman_environment.json`** - (Alternative location)

## Test Coverage

### Sanity Collection (End-to-End Flow)
Verifies the complete flow from order creation to consumer processing:

1. **Health Check (Consumer)** - Verifies Kafka connectivity is UP
2. **Create Order (Producer)** - Creates a new order with auto-generated hex OrderID
3. **Get Order Details - Initial State (Consumer)** - Confirms CREATED status and shipping cost calculation
4. **Update Order Status (Producer)** - Updates order to DISPATCHED status
5. **Verify Sequential Update (Consumer)** - Confirms Consumer reflected DISPATCHED status
6. **Resilience - Invalid Transition** - Attempts backward transition (DISPATCHED → CREATED) and verifies Consumer ignores it
7. **Diagnostic Check (Consumer)** - Confirms OrderID exists in the Kafka topic (**NOW USES GET** ✅)

### Consumer-Endpoints Collection (NEW - Detailed Testing)
Comprehensive testing of all consumer endpoints with validation scripts:

**Health & Status:**
- Root Endpoint - API metadata and documentation
- Liveness Probe - Service availability
- Readiness Probe - Kafka connectivity status

**Order Operations (NOW USING GET - NOT POST ✅):**
- Get Order Details - Retrieves order by ID with full validation
- Get All Orders From Topic - Lists all orders from Kafka topic with validation

**Integration Tests:**
- Test Flow sequence for end-to-end scenarios

## HTTP Method Changes (Exercise 2)

### Before
```
POST /order-details with JSON body {"orderId": "001"}
POST /getAllOrdersFromTopic with JSON body {"topicName": "orders"}
```

### After (✅ Correct REST Semantics)
```
GET /order-details?orderId=001
GET /getAllOrdersFromTopic?topicName=orders
```

**Reason**: These are read-only operations. GET is semantically correct for safe, idempotent queries.

## Validation Scripts

All endpoints include comprehensive Postman test scripts that validate:

### getOrderDetails Tests
- ✅ Status code is 200
- ✅ All required order fields present
- ✅ Order ID matches request parameter
- ✅ Status is valid (CREATED|CONFIRMED|DISPATCHED|DELIVERED)
- ✅ Shipping cost is numeric and non-negative
- ✅ Order has items
- ✅ Total amount is positive
- ✅ Currency is USD

### getAllOrdersFromTopic Tests
- ✅ Status code is 200
- ✅ Response has required fields (topic, orderCount, orderIds, timestamp)
- ✅ Topic name matches request parameter
- ✅ Order count matches array length
- ✅ orderIds is an array
- ✅ Timestamp is numeric
- ✅ All order IDs have ORD- prefix

### Root Endpoint Tests
- ✅ Service metadata is correct
- ✅ Endpoints are documented
- ✅ Health endpoints use GET
- ✅ Order endpoints use GET (not POST)

## Prerequisites

Ensure both services are running:
- **Producer (Cart Service)**: `http://localhost:8081`
- **Consumer (Order Service)**: `http://localhost:8082`

### Starting Services with Docker Compose

```bash
# Start Producer (includes Kafka and Zookeeper)
cd producer
docker-compose up -d

# Start Consumer (connects to existing Kafka)
cd consumer
docker-compose up -d
```

## Usage

### Option 1: Postman GUI

1. Open Postman
2. Import the environment:
   - Click "Import" → Select `EDA-Local.postman_environment.json`
3. Import collection(s):
   - Click "Import" → Select `Sanity.postman_collection.json` (end-to-end)
   - OR Select `Consumer-Endpoints.postman_collection.json` (detailed consumer tests)
4. Select "EDA-Local" environment from the dropdown (top-right)
5. Run the collection:
   - Click "Run" button
   - OR Select individual requests and send them

### Option 2: Postman CLI (newman)

```bash
# Install newman
npm install -g newman

# Run Sanity collection
newman run Sanity.postman_collection.json \
  -e EDA-Local.postman_environment.json \
  --reporters cli,json \
  --reporter-json-export results.json

# Run Consumer endpoints collection
newman run Consumer-Endpoints.postman_collection.json \
  -e EDA-Local.postman_environment.json \
  --reporters cli,json
```

### Option 3: Command Line (curl)

```bash
# Get order details
curl "http://localhost:8082/order-service/order-details?orderId=001"

# Get all orders from topic
curl "http://localhost:8082/order-service/getAllOrdersFromTopic?topicName=orders"

# Check readiness
curl "http://localhost:8082/order-service/health/ready"
```

   - Click "Run" button
   - Click "Run Sanity" to execute all tests

### Option 2: Newman CLI

```bash
# Install Newman (if not already installed)
npm install -g newman

# Run the test suite
newman run Sanity.postman_collection.json -e EDA-Local.postman_environment.json

# Run with detailed output
newman run Sanity.postman_collection.json -e EDA-Local.postman_environment.json --verbose

# Run and generate HTML report
newman run Sanity.postman_collection.json -e EDA-Local.postman_environment.json \
  --reporters cli,html --reporter-html-export report.html
```

## API Endpoints

### Producer (Cart Service) - Port 8081

- `POST /cart-service/create-order` - Create new order
  ```json
  {
    "orderId": "A1B2C3D4E5F60789",
    "numItems": 5
  }
  ```

- `PUT /cart-service/update-order` - Update order status
  ```json
  {
    "orderId": "ORD-A1B2C3D4E5F60789",
    "status": "DISPATCHED"
  }
  ```

### Consumer (Order Service) - Port 8082

- `GET /order-service/health/ready` - Readiness probe (includes Kafka check)

- `POST /order-service/order-details` - Get order details
  ```json
  {
    "orderId": "A1B2C3D4E5F60789"
  }
  ```

- `POST /order-service/getAllOrdersFromTopic` - Get all order IDs from topic
  ```json
  {
    "topicName": "orders"
  }
  ```

## Expected Results

All 7 tests should pass when services are healthy:

```
✓ Health Check (Consumer)
  ✓ Status code is 200
  ✓ Kafka connection is UP
  
✓ Create Order (Producer)
  ✓ Order Created successfully
  ✓ Returns valid OrderID
  
✓ Get Order Details - Initial State (Consumer)
  ✓ Status code is 200
  ✓ Shipping cost is calculated and status is CREATED
  
✓ Update Order Status (Producer)
  ✓ Update accepted by Producer
  
✓ Verify Sequential Update (Consumer)
  ✓ Status code is 200
  ✓ Consumer reflected DISPATCHED status
  
✓ Resilience - Invalid Transition (Consumer Check)
  ✓ Producer did not crash
  ✓ Verification: Consumer ignored old status
  
✓ Diagnostic Check (Consumer)
  ✓ Status code is 200
  ✓ Topic contains our OrderID
```

## Troubleshooting

### Services Not Running

If health check fails with connection refused:
```bash
# Check if services are running
docker ps

# Check service logs
docker logs producer-app-1
docker logs consumer-app-1
```

### Kafka Not Ready

If Kafka check reports DOWN:
```bash
# Check Kafka logs
docker logs producer-kafka-1

# Restart services
cd producer && docker-compose restart
cd consumer && docker-compose restart
```

### Consumer Lag

If Sequential Update test fails (Consumer hasn't processed the update yet):
- Wait a few seconds and re-run the test
- Check consumer logs for processing delays
- Verify Kafka consumer is running and not blocked

## Notes

- **OrderID Format**: The test generates a random 16-character hex ID (e.g., "A1B2C3D4E5F60789"), which the Producer normalizes to "ORD-####" format
- **Topic Name**: The diagnostic check uses topic name "orders" (configured in docker-compose)
- **Async Processing**: Consumer processes events asynchronously, so there may be a small delay between Producer update and Consumer state change
- **Idempotency**: The Consumer handles duplicate messages gracefully (At-Least-Once delivery)
- **Sequencing**: The Consumer validates state transitions and rejects invalid backward transitions
