# Exercise 1: RabbitMQ E-commerce Backend Requirements

## 1. Scenario
Build a simplified backend system for an e-commerce platform. [cite_start]When a customer places an order, details must be broadcast to multiple downstream services via RabbitMQ.

## 2. Producer (Cart Service)
* [cite_start]**Endpoint:** `POST /create-order`.
* **Input:** Receives only `orderId` and `numItems`. [cite_start]All other fields (customerId, orderDate, items array, totalAmount, currency, status) must be randomly auto-generated internally.
* [cite_start]**Order Structure:** Must strictly follow the specified JSON schema (ISO 8601 date, decimal prices, etc.).
* [cite_start]**Serialization:** Objects must be serialized as JSON before publishing.
* **Validation:** Strictly validate inputs. [cite_start]If validation fails, return a clear error message with an appropriate error code (e.g., 400 Bad Request).

> **Design Comment:** Use a **Topic Exchange** rather than a Fanout exchange. [cite_start]While the goal is broadcasting, a Topic exchange allows consumers to filter messages (e.g., status-based routing) without changing the producer logic[cite: 15, 17].

## 3. Consumer (Order Service)
* [cite_start]**Connectivity:** Connect to RabbitMQ and receive ONLY events where the status is "new".
* **Actions:**
  1. [cite_start]Calculate Shipping: `shippingCost = 2% of totalAmount`.
  2. [cite_start]Persistence: Log order details and store them (including shipping cost) in an in-memory database.
* **Endpoint:** `GET /order-details` (input: `orderId`). [cite_start]Returns full details plus shipping cost.

> **Design Comment:** The consumer should declare its own queue and binding. [cite_start]This ensures the queue is ready to receive messages even if the consumer starts after the producer has sent data[cite: 16].

## 4. Submission & Grading
* [cite_start]**Deliverables:** 2 `docker-compose.yml` files (one per service) and a `README.md`.
* [cite_start]**README Content:** Full Name/ID, API URLs, Exchange type/justification, Binding key/justification, and service declaration logic.
* [cite_start]**Error Codes:** Return correct HTTP error codes (e.g., 500) for failures.