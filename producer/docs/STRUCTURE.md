# Producer Module – Project Structure

## Directory Layout

```text
producer/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── generated-requests.http              # HTTP request templates for manual testing
├── src/main/
│   ├── java/mta/eda/producer/
│   │   ├── Producer.java                      # Spring Boot entry point
│   │   ├── controller/
│   │   │   └── OrderController.java           # REST endpoints (root, health, orders)
│   │   ├── service/
│   │   │   ├── kafka/
│   │   │   │   ├── KafkaProducerService.java  # Kafka publishing layer (sync)
│   │   │   │   └── KafkaHealthService.java    # Kafka connectivity health checks
│   │   │   ├── order/
│   │   │   │   └── OrderService.java          # Business logic & coordination
│   │   │   └── utils/
│   │   │       └── OrderUtils.java            # Order generation utilities
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
│   │       ├── DuplicateOrderException.java   # Duplicate orderId exception
│   │       ├── OrderNotFoundException.java    # Order not found exception
│   │       ├── InvalidOrderIdException.java   # Invalid orderId format exception
│   │       └── README.md                      # Error handling documentation
│   └── resources/
│       └── application.properties             # Kafka + app config + logging
└── docs/
    ├── planing.md                             # Implementation plan
    └── STRUCTURE.md                           # This file
```

## Component Overview

### Controllers
- **OrderController**: REST API endpoints for health checks and order operations

### Services
- **KafkaProducerService**: Synchronous Kafka message publishing with error handling
- **KafkaHealthService**: Kafka connectivity and topic existence checks for health endpoints
- **OrderService**: Business logic for order creation/update and ID normalization

### Models
- **Order**: Full order payload published to Kafka
- **OrderItem**: Line item within an order
- **CreateOrderRequest**: DTO for POST /create-order
- **UpdateOrderRequest**: DTO for PUT /update-order

### Configuration
- **KafkaProducerConfig**: Kafka producer settings (acks=all, idempotence, etc.)
- **KafkaTopicConfig**: Topic creation and configuration

### Exception Handling
- **GlobalExceptionHandler**: Centralized exception handling for all endpoints
- **ProducerSendException**: Kafka send failures (TIMEOUT, INTERRUPTED, KAFKA_ERROR)
- **DuplicateOrderException**: Order already exists (409 Conflict)
- **OrderNotFoundException**: Order not found (404 Not Found)
- **InvalidOrderIdException**: Invalid orderId format (400 Bad Request)

## Key Files

### Configuration
- `application.properties`: All Kafka, logging, and application settings
- `pom.xml`: Maven dependencies and build configuration

### Testing
- `generated-requests.http`: Manual HTTP testing templates

### Deployment
- `Dockerfile`: Multi-stage Docker build
- `docker-compose.yml`: Full stack (Zookeeper + Kafka + Producer)

### Documentation
- `planing.md`: Complete implementation plan
- `STRUCTURE.md`: This project structure reference

