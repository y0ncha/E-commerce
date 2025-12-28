# Exercise 2: Kafka E-commerce Backend Requirements

## 1. Scenario
Evolve the e-commerce system to use Kafka. [cite_start]The critical requirement is that updates for any specific order must be processed in the **correct sequence** to ensure system consistency.

## 2. Producer (Cart Service)
* [cite_start]**Endpoint 1:** `POST /create-order` (Same logic as Ex. 01).
* **Endpoint 2:** `PUT /update-order`: Receives `orderId` and `status`. [cite_start]Updates the order internally and notifies all consumers of the change.
* [cite_start]**Requirements:** Handle connection issues and broker unavailability; return correct API responses.

> **Design Comment:** To guarantee the "correct sequence" requirement, you MUST use the `orderId` as the **Message Key**. [cite_start]This ensures all events for the same order land in the same Kafka partition, where strict ordering is maintained.

## 3. Consumer (Order Service)
* [cite_start]**Ordering:** Receive all events and handle them according to the correct order they happened.
* [cite_start]**Endpoint 1:** `GET /getAllOrderIdsFromTopic`: Receives a topic name (string) and prints all unique `orderId`s received from that topic.
* **Endpoint 2:** `GET /order-details`: Same as Ex. [cite_start]01.

> **Design Comment:** Set `EnableAutoCommit = false` and implement manual acknowledgments. [cite_start]This ensures "At-Least-Once" delivery, as offsets are only committed after the business logic (shipping calculation/storage) is successful[cite: 2, 19].

## 4. Grading & Bonus
* [cite_start]**Submission:** All applications must run in Docker containers.
* [cite_start]**Serialization:** Use local JSON conversion by default.
* [cite_start]**Bonus (+10 pts):** Use Avro format with a Schema Registry.
* [cite_start]**README:** Justify the choice of Message Key and detail error-handling strategies.