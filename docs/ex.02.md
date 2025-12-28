# Exercise 2: Simplified E-commerce Backend (Kafka)

## Scenario
[cite_start]A backend system for an e-commerce platform where order details are sent to multiple downstream services via Kafka[cite: 59, 60]. [cite_start]Updates for any given order must be processed in the **correct sequence** to ensure system consistency[cite: 61].

## Requirements

### 1. Producer Application (Cart Service)
* [cite_start]**API Endpoint (`/create-order`):** Same functionality as Exercise 1[cite: 64, 65].
* **API Endpoint (`/update-order`):** Receives `orderId` and `status`. [cite_start]Updates the order internally and notifies all relevant consumers[cite: 66, 67].
* [cite_start]**Order Structure:** Same as Exercise 1, using ISO 8601 for dates[cite: 69].

> **Design Tip:** To ensure "correct sequence" processing for a specific order, you **must use the `orderId` as the message key**. [cite_start]This ensures all updates for the same order land in the same Kafka partition and are processed in order.

### 2. Consumer Application (Order Service)
* [cite_start]**Kafka Connectivity:** Connect to Kafka, receive all order events, and handle them in the correct sequence[cite: 71].
* [cite_start]**API Endpoint (`/getAllOrderIdsFromTopic`):** Receives a topic name (string) and prints all `orderId`s received from that topic[cite: 72].
* [cite_start]**API Endpoint (`/order-details`):** Same as Exercise 1; returns order details and calculated shipping costs[cite: 74].

> [cite_start]**Design Tip:** Use different Consumer Groups if you want multiple services (Shipping, Billing, etc.) to each receive every message independently[cite: 60].

## Important Instructions
1.  **Error Handling:** Handle connection issues and broker unavailability. [cite_start]Return correct HTTP responses (GET/POST/PUT)[cite: 76, 78].
2.  [cite_start]**Tools:** Use all EDA tools and approaches learned in the course (e.g., retries, dead-letter logic)[cite: 79].
3.  [cite_start]**Docker:** All applications must be submitted in Docker containers[cite: 81].
4.  **Serialization:** Default to string values with local JSON conversion. [cite_start]**Bonus (+10 points):** Use a Schema Registry with Avro format[cite: 82, 83].

## Readme Requirements
Attach a `README.md` with:
* [cite_start]Full Name and ID[cite: 84].
* [cite_start]Topic names and their purposes[cite: 85].
* [cite_start]The **Message Key** used and the justification for it[cite: 86].
* [cite_start]Detailed error-handling strategies and the reasoning behind them[cite: 87].