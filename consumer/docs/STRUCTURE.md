# Consumer Module – Project Structure

## Directory Layout

```text
consumer/
├── pom.xml
├── Dockerfile
├── src/main/
│   ├── java/mta/eda/consumer/
│   │   ├── Consumer.java                      # Spring Boot entry point
│   │   ├── controller/
│   │   │   └── OrderController.java           # REST endpoints (/order-details/{orderId}, /getAllOrderIdsFromTopic)
│   │   ├── service/
│   │   │   ├── kafka/
│   │   │   │   ├── KafkaConsumerService.java  # Kafka listener with Manual Offset Management
│   │   │   │   └── KafkaHealthService.java    # Kafka connectivity health checks
│   │   │   ├── order/
│   │   │   │   └── OrderService.java          # State management (Map) and shipping cost logic
│   │   │   └── utils/
│   │   │       └── ShippingCalculator.java    # Business logic for shipping costs
│   │   ├── model/
│   │   │   ├── Order.java                     # Order domain model (Kafka payload)
│   │   │   └── OrderItem.java                 # Order line item
│   │   ├── config/
│   │   │   └── KafkaConsumerConfig.java       # Kafka consumer configuration (Manual Commit)
│   │   └── exception/
│   │       ├── GlobalExceptionHandler.java    # Centralized error handling
│   │       ├── OrderNotFoundException.java    # Order not found (404)
│   │       └── BrokerUnavailableException.java # Kafka broker unavailable (503)
│   └── resources/
│       └── application.properties             # Consumer group, manual offset properties
└── docs/
    ├── PLAN.md                                # Implementation plan
    └── STRUCTURE.md                           # This file
```

## Component Overview

### Controllers
- **OrderController**: REST API for order inspection and diagnostic topic scanning.

### Services
- **KafkaConsumerService**: Kafka listener responsible for processing messages and manual offset commits.
- **KafkaHealthService**: Broker connectivity checks for health monitoring.
- **OrderService**: Local state management using a `Map` for idempotency and shipping cost calculation coordination.
- **ShippingCalculator**: Business logic for calculating shipping costs based on order details.

### Models
- **Order**: Shared domain model used for deserializing Kafka messages.
- **OrderItem**: Line item within an order, matching the producer's structure.

### Configuration
- **KafkaConsumerConfig**: Configuration for manual acknowledgement mode and consumer factory settings.

### Exception Handling
- **GlobalExceptionHandler**: Centralized mapping of infrastructure and logic errors to HTTP status codes.
- **OrderNotFoundException**: Mapped to 404 Not Found when an order is missing from local state.
- **BrokerUnavailableException**: Mapped to 503 Service Unavailable for infrastructure issues.

## Key Files

### Configuration
- `application.properties`: Consumer group ID, manual offset properties, and Kafka settings.
- `pom.xml`: Maven dependencies and build configuration.

### Deployment
- `Dockerfile`: Multi-stage Docker build for the consumer service.

### Documentation
- `PLAN.md`: Complete implementation plan for the consumer.
- `STRUCTURE.md`: This project structure reference.
