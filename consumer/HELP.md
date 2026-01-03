#!/bin/bash
# HELP.md - Quick Reference for Running the Order Service (Consumer)

## 🚀 Quick Start

### Important: Start Producer First!
The consumer depends on Kafka, which is managed by the producer's docker-compose.

#### Step 1: Start Producer (Kafka + Zookeeper + Producer Service)
```bash
cd ../producer
docker-compose up -d
```

#### Step 2: Start Consumer (Order Service)
```bash
cd ../consumer
docker-compose up -d
```

### Default Setup (topic: "orders")
Once both are running, the consumer automatically connects to Kafka and listens for messages.

### Custom Topic Name
```bash
cd consumer
KAFKA_TOPIC=my-custom-topic docker-compose up -d
```

### View Logs
```bash
docker-compose logs -f order-service
```

### Verify Health
```bash
curl http://localhost:8081/actuator/health
```

### Stop Everything
```bash
# Stop consumer first
cd consumer
docker-compose down

# Then stop producer (includes Kafka)
cd ../producer
docker-compose down
```

---

## 📋 Architecture

### Two Docker Compose Files:
```
producer/docker-compose.yml  (Manages: Kafka, Zookeeper, Producer)
consumer/docker-compose.yml  (Manages: Consumer only)
                             Both connect via ecommerce-network
```

**Why two files?** Kafka and Zookeeper are infrastructure that should be managed once. The consumer simply connects to the shared Kafka broker.

---

## 📋 Configuration

### Environment Variables (In .env or command line)
```bash
KAFKA_TOPIC=orders                    # Default: orders
SPRING_KAFKA_BOOTSTRAP_SERVERS=general:29092
SPRING_KAFKA_CONSUMER_GROUP_ID=order-service-group
SERVER_PORT=8081
LOGGING_LEVEL_MTA_EDA_CONSUMER=DEBUG
```

### Using .env File
```bash
cp .env.example .env
nano .env                             # Edit as needed
docker-compose up -d
```

---

## 🔍 Key Features Implemented (Phase 2)

✅ **At-Least-Once Delivery**
   - Manual offset management with acknowledgment
   - Messages only acknowledged after state update

✅ **Idempotency Check**
   - Detects and skips duplicate events
   - Prevents double-processing

✅ **State Machine Validation**
   - Enforces: CREATED → CONFIRMED → DISPATCHED → DELIVERED
   - Rejects out-of-order events

✅ **Shipping Cost Calculation**
   - Formula: $5 + ($0.50 × items) + (2% × total)
   - Deterministic and reproducible

✅ **Poison Pill Handling**
   - Acknowledges malformed messages
   - Prevents consumer from blocking

---

## 📊 Example: Listening to Custom Topic

### Step 1: Start Producer
```bash
cd ../producer
docker-compose up -d
```

### Step 2: Start Consumer with Custom Topic
```bash
cd ../consumer
KAFKA_TOPIC=orders-v2 docker-compose up -d
```

### Step 3: Verify Consumer is Connected
```bash
docker-compose logs order-service | grep "Received message"
```

### Step 4: Produce a Message (from producer)
```bash
cd ../producer
KAFKA_TOPIC=orders-v2 ./mvnw spring-boot:run
```

### Step 5: Check Consumer Processed It
```bash
cd ../consumer
docker-compose logs order-service | grep "Created order\|Duplicate\|Invalid"
```

---

## 🔧 Troubleshooting

### Consumer Won't Start
```bash
# Check if producer (Kafka) is running
docker ps | grep general

# If not, start producer first
cd ../producer
docker-compose up -d

# Then retry consumer
cd ../consumer
docker-compose up -d
```

### Connection Refused to Kafka
```bash
# Verify Kafka is accessible
docker-compose logs order-service | grep -i "connection\|error"

# Make sure both services are on same network
docker network inspect ecommerce-network
```

### Port 8081 Already in Use
```bash
# Use different port
SERVER_PORT=8082 docker-compose up -d
```

### Messages Not Being Processed
```bash
# Check consumer group
docker-compose exec general general-consumer-groups.sh --bootstrap-server general:29092 --list

# Check topic exists
docker-compose exec general general-topics.sh --bootstrap-server general:29092 --list
```

---

## 📝 Log Levels

### View Full Debug Logs
```bash
LOGGING_LEVEL_MTA_EDA_CONSUMER=TRACE docker-compose up -d
docker-compose logs -f order-service
```

### Production (Less Verbose)
```bash
LOGGING_LEVEL_MTA_EDA_CONSUMER=WARN docker-compose up -d
```

---

## 🧪 Testing

### Produce Test Messages
```bash
# From producer directory
cd ../producer
KAFKA_TOPIC=orders ./mvnw spring-boot:run
```

### Verify Consumer State
```bash
# Check health
curl http://localhost:8081/actuator/health

# Get all orders (Phase 3 endpoint)
curl http://localhost:8081/getAllOrders 2>/dev/null | jq .
```

---

## 🐳 Docker Services

**Producer Stack:**
- Kafka broker (port 29092)
- Zookeeper (port 2181)
- Producer Service (port 8080)

**Consumer Stack:**
- Order Service (port 8081)

All connected via `ecommerce-network`.

---

## 💾 Data Persistence

⚠️ **NOTE**: Current implementation uses in-memory storage (`ConcurrentHashMap`).

When the container stops:
- ❌ All order state is lost
- ✅ Next startup will reprocess events from Kafka (if offset is reset)

For persistence (Phase 4+):
- Implement database backend
- Or use Kafka topics as the source of truth

---

## 🎯 Next Steps (Phase 3)

- [ ] Implement REST API endpoints for order queries
- [ ] Add distributed tracing
- [ ] Implement metrics collection
- [ ] Add circuit breaker for resilience

---

## ❓ FAQ

**Q: Do I need to start producer first?**
A: Yes! Consumer depends on Kafka which is managed by producer's docker-compose.

**Q: How do I change the topic?**
A: Use `KAFKA_TOPIC=my-topic docker-compose up -d`

**Q: Where is the order state stored?**
A: In-memory `ConcurrentHashMap` - lost on restart

**Q: What happens if Kafka broker goes down?**
A: Consumer stops receiving messages but doesn't crash. Restarts automatically when Kafka is back.

**Q: Can I run multiple consumer instances?**
A: Yes! They'll share the same consumer group and partition events across instances.

**Q: How do I see what orders are processed?**
A: Check logs with `docker-compose logs order-service | grep "Created\|Transitioned"`

---

## 📚 Full Documentation

- **README.md**: Complete architecture & features
- **PLAN.md**: Phase-by-phase implementation plan
- **ERRORS.md**: Error handling mechanisms
- **STRUCTURE.md**: Project folder organization


---

## 🔍 Key Features Implemented (Phase 2)

✅ **At-Least-Once Delivery**
   - Manual offset management with acknowledgment
   - Messages only acknowledged after state update

✅ **Idempotency Check**
   - Detects and skips duplicate events
   - Prevents double-processing

✅ **State Machine Validation**
   - Enforces: CREATED → CONFIRMED → DISPATCHED → DELIVERED
   - Rejects out-of-order events

✅ **Shipping Cost Calculation**
   - Formula: $5 + ($0.50 × items) + (2% × total)
   - Deterministic and reproducible

✅ **Poison Pill Handling**
   - Acknowledges malformed messages
   - Prevents consumer from blocking

---

## 📊 Example: Listening to Custom Topic

### Step 1: Start with Custom Topic
```bash
KAFKA_TOPIC=orders-v2 docker-compose up -d
```

### Step 2: Verify Consumer is Connected
```bash
docker-compose logs order-service | grep "Received message"
```

### Step 3: Produce a Message (from producer)
```bash
cd ../producer
KAFKA_TOPIC=orders-v2 ./mvnw spring-boot:run
```

### Step 4: Check Consumer Processed It
```bash
docker-compose logs order-service | grep "Created order\|Duplicate\|Invalid"
```

---

## 🔧 Troubleshooting

### Service Won't Start
```bash
# Check logs
docker-compose logs order-service

# Check if port 8081 is in use
lsof -i :8081

# Use different port
SERVER_PORT=8082 docker-compose up -d
```

### Kafka Connection Error
```bash
# Verify Kafka is running
docker ps | grep general

# Check Kafka health
docker-compose logs general | grep -i error
```

### Messages Not Being Processed
```bash
# Check consumer group
docker-compose exec general general-consumer-groups.sh --bootstrap-server localhost:29092 --list

# Check topic
docker-compose exec general general-topics.sh --bootstrap-server localhost:29092 --list
```

### High CPU/Memory Usage
```bash
# Check current state size
docker exec order-service curl http://localhost:8081/getAllOrders | wc -l

# Restart consumer
docker-compose restart order-service
```

---

## 📝 Log Levels

### View Full Debug Logs
```bash
LOGGING_LEVEL_MTA_EDA_CONSUMER=TRACE docker-compose up -d
docker-compose logs -f order-service
```

### Production (Less Verbose)
```bash
LOGGING_LEVEL_MTA_EDA_CONSUMER=WARN docker-compose up -d
```

---

## 🧪 Testing

### Produce Test Messages
```bash
# From producer directory
cd ../producer
KAFKA_TOPIC=orders ./mvnw spring-boot:run
```

### Verify Consumer State
```bash
# Check health
curl http://localhost:8081/actuator/health

# Get all orders (Phase 3 endpoint)
curl http://localhost:8081/getAllOrders 2>/dev/null | jq .
```

---

## 🐳 Docker Compose Structure

```
services:
  order-service:        ← Your consumer application
  kafka:               ← Kafka broker (port 29092 internal)
  zookeeper:           ← Kafka metadata management
```

---

## 💾 Data Persistence

⚠️ **NOTE**: Current implementation uses in-memory storage (`ConcurrentHashMap`).

When the container stops:
- ❌ All order state is lost
- ✅ Next startup will reprocess events from Kafka (if offset is reset)

For persistence (Phase 4+):
- Implement database backend
- Or use Kafka topics as the source of truth

---

## 🎯 Next Steps (Phase 3)

- [ ] Implement REST API endpoints for order queries
- [ ] Add distributed tracing
- [ ] Implement metrics collection
- [ ] Add circuit breaker for resilience

---

## ❓ FAQ

**Q: How do I change the topic?**
A: Use `KAFKA_TOPIC=my-topic docker-compose up -d`

**Q: Where is the order state stored?**
A: In-memory `ConcurrentHashMap` - lost on restart

**Q: What happens if Kafka broker goes down?**
A: Consumer stops receiving messages but doesn't crash. Restarts automatically when Kafka is back.

**Q: Can I run multiple consumer instances?**
A: Yes! They'll share the same consumer group and partition events across instances.

**Q: How do I see what orders are processed?**
A: Check logs with `docker-compose logs order-service | grep "Created\|Transitioned"`

---

## 📚 Full Documentation

- **README.md**: Complete architecture & features
- **PLAN.md**: Phase-by-phase implementation plan
- **ERRORS.md**: Error handling mechanisms
- **STRUCTURE.md**: Project folder organization

