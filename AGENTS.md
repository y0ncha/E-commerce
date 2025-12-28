# PROJECT SPECIFICATION: Event-Driven E-Commerce System (Exercise 02)

## 1. Project Overview
A Spring Boot based system implementing an Event-Driven Architecture (EDA) using Apache Kafka.
* **Goal:** Decouple order creation (Cart) from order processing (Shipping).
* **Key Constraint:** Preserve order of events per `orderId`.

## 2. Infrastructure
* **Root Folder:** `eda-ex2-solution`
* **Build Tool:** Maven (Multi-module strategy)
* **Containerization:** Docker Compose
    * **Zookeeper:** Port 2181
    * **Kafka:** Port 9092 (Exposed to host)
    * **Environment:** `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`

## 3. Microservices Structure

### Service A: Cart Service (Producer)
* **ArtifactId:** `cart-service`
* **Port:** `8080`
* **Role:** REST API -> Validates Order -> Publishes to Kafka.
* **Kafka Config:**
    * `acks=all` (Durability)
    * `retries=5` (Resilience)
    * `key-serializer`: StringSerializer
    * `value-serializer`: JsonSerializer

### Service B: Shipping Service (Consumer)
* **ArtifactId:** `shipping-service`
* **Port:** `8081`
* **Role:** Listens to Kafka -> Calculates Shipping -> Stores/Logs.
* **Kafka Config:**
    * `group-id`: `shipping-group`
    * `enable-auto-commit`: `false` (Manual Commit strategy)
    * `key-deserializer`: StringDeserializer
    * `value-deserializer`: JsonDeserializer

## 4. Data Contract
* **Topic Name:** `orders-events`
* **Message Key:** `orderId` (Crucial for partition ordering)
* **Payload (JSON):**
    ```json
    {
      "orderId": "String",
      "customerId": "String",
      "orderDate": Long,
      "totalAmount": BigDecimal,
      "status": "String",
      "items": [ { "itemId": "String", "quantity": int, "price": BigDecimal } ]
    }
    ```

## 5. API Requirements
1.  **POST** `/create-order`: Publish `OrderCreated` event.
2.  **PUT** `/update-order`: Publish `OrderUpdated` event.
3.  **GET** `/getAllOrderIdsFromTopic`: Read unique IDs from topic history.
4.  **GET** `/order-details/{orderId}`: Return object + shipping cost.

## 6. Error Handling Strategy
* **Producer:** Try-catch `ProduceException`. Return HTTP 500 if Broker is down.
* **Consumer:** Manual `acknowledgment.acknowledge()` only after successful logic.
* **Poison Pill:** Catch deserialization errors -> Log -> Commit -> Continue.