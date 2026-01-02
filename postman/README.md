# Postman Sanity Test Suite for Exercise 2

This directory contains an automated sanity test suite for verifying the resilience and sequencing standards of Exercise 2 (Kafka E-commerce Backend).

## Files

- **`EDA-Local.postman_environment.json`** - Environment configuration for local testing
- **`Sanity.postman_collection.json`** - Automated test collection with 7 requests

## Test Coverage

The suite verifies the complete end-to-end flow from order creation in the Cart Service (Producer) to state mirroring and shipping calculation in the Order Service (Consumer):

1. **Health Check (Consumer)** - Verifies Kafka connectivity is UP
2. **Create Order (Producer)** - Creates a new order with auto-generated hex OrderID
3. **Get Order Details - Initial State (Consumer)** - Confirms CREATED status and shipping cost calculation
4. **Update Order Status (Producer)** - Updates order to DISPATCHED status
5. **Verify Sequential Update (Consumer)** - Confirms Consumer reflected DISPATCHED status
6. **Resilience - Invalid Transition** - Attempts backward transition (DISPATCHED → CREATED) and verifies Consumer ignores it
7. **Diagnostic Check (Consumer)** - Confirms OrderID exists in the Kafka topic

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
3. Import the collection:
   - Click "Import" → Select `Sanity.postman_collection.json`
4. Select "EDA-Local" environment from the dropdown (top-right)
5. Run the collection:
   - Open "Sanity" collection
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
