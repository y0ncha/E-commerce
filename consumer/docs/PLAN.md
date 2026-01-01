# PLAN.md: Consumer Service (Order Service) Implementation

## 1. Project Overview
The **Order Service** acts as the consumer in our system. It is responsible for listening to order events (creation and updates) from Kafka, maintaining an internal state of all orders, and providing API access to query that state.

## 2. Phase 1: Kafka Consumer Configuration
To meet the course requirements for reliability and "At-Least-Once" delivery, we must configure the consumer carefully.

* **Key Properties (`application.properties`):**
    * `bootstrap.servers`: Kafka broker address (e.g., `kafka:29092`).
    * `group.id`: `order-service-group` (to allow for scaling/restarts).
    * **Crucial:** `enable.auto.commit=false`. We will manually manage offsets.
    * `key.deserializer`: `StringDeserializer`.
    * `value.deserializer`: `StringDeserializer` (we will use `JsonConverter` locally as per Ex 2 instructions).
* **Topic Subscription:** Subscribe to the `orders` topic.

## 3. Phase 2: Core Event Processing Logic
This phase involves the background loop that polls Kafka and updates the local state.

* **Message Sequencing:** * Because the Producer uses `orderId` as the message key, Kafka ensures all events for a specific order arrive in the same partition.
    * The consumer will process these in the exact order they were produced (e.g., `Created` → `Dispatched`).
* **Manual Offset Management:**
    1.  Poll for records.
    2.  For each record:
        * Deserialize JSON to an internal `Order` object.
        * Update the local `Map<String, Order>` (state).
    3.  **`commitSync()`**: Only call this after the local state has been successfully updated.
* **Idempotency:** * Check if the incoming update is older than the current state (e.g., don't let a "Created" event overwrite a "Delivered" state if messages are redelivered).

## 4. Phase 3: API Endpoint Implementation
The consumer must expose two primary GET endpoints for the system to be functional.

* **`GET /order-details/{orderId}`**
    * Retrieve the order from the local Map.
    * Calculate shipping costs using the logic from Exercise 1.
    * **Error Handling:** If the ID doesn't exist, return `404 Not Found`.
* **`GET /getAllOrderIdsFromTopic?topic={topicName}`**
    * This is a diagnostic endpoint.
    * Iterate through the processed records and return a list of unique `orderId`s seen so far.

## 5. Phase 4: Resilience & Error Handling
We must ensure the service doesn't crash and provides meaningful feedback.

* **Broker Unavailability:**
    * If the Kafka broker is down at startup, the service should log the error and retry.
    * If an API call depends on a live connection that is lost, return an HTTP `503 Service Unavailable`.
* **Poison Pill Handling:**
    * Wrap the JSON deserialization in a `try-catch`.
    * If a message is malformed, log the "poison pill" and move to the next offset to prevent the consumer from getting stuck in an infinite loop.
* **Validation:** * Validate that the incoming event contains a valid `orderId` before processing.

## 6. Phase 5: Docker Orchestration
* **Dockerfile:** Build the service into an image.
* **docker-compose.yml:** * Add the `order-service` (Consumer).
    * Set `depends_on: [kafka]`.
    * Pass the Kafka address via environment variables.

---

### Educational Justification (MTA EDA Standards)
* **Why `EnableAutoCommit = false`?** This ensures that if the consumer crashes mid-processing, the message is not marked as "read" by the broker. Upon restart, the consumer will re-read the message, guaranteeing "At-Least-Once" delivery.
* **Why use Message Keys?** Using `orderId` as a key guarantees that all updates for a single order are handled by the same consumer instance in the correct chronological order, preserving data consistency.
* **Why Idempotent processing?** Since we use "At-Least-Once" delivery, the same message might be delivered twice. Our logic must handle this without corrupting the state.

---

**Next Step:** I will wait for your confirmation to begin **Step 1: Configuring the Kafka Consumer Bean and Properties**.