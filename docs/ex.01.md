# Exercise 1: Simplified E-commerce Backend (RabbitMQ)

## Scenario
You are building a simplified backend system for an e-commerce platform. [cite_start]When a customer places an order, the order details must be broadcast to multiple downstream services (e.g., inventory management, billing, and shipping) via RabbitMQ[cite: 1, 2].

## Requirements

### 1. Producer Application (Cart Service)
* [cite_start]**API Endpoint (`/create-order`):** Accepts a request to create a new order[cite: 5].
* **Input:** Receives `orderId` and the number of items. [cite_start]The rest of the fields (customerId, date, item details, price) should be generated randomly internally[cite: 7].
* **Order Object Structure:**
    * [cite_start]`orderId`: string (Unique identifier) [cite: 9]
    * [cite_start]`customerId`: string [cite: 9]
    * [cite_start]`orderDate`: ISO 8601 timestamp [cite: 9]
    * [cite_start]`items`: Array of objects (itemId, quantity > 0, price) [cite: 9]
    * [cite_start]`totalAmount`: decimal (Sum of items) [cite: 9]
    * [cite_start]`currency`: string (e.g., USD) [cite: 9]
    * [cite_start]`status`: string (e.g., 'pending', 'confirmed') [cite: 9]
* [cite_start]**RabbitMQ Publishing:** Publish the order as a JSON-serialized event[cite: 31, 32].
* **Validation:** Ensure input fields are valid. [cite_start]Return clear error messages for failures[cite: 34, 36].

> **Design Tip:** Only the producer should declare the exchange. [cite_start]While the prompt mentions "broadcast," using a **Topic Exchange** instead of a Fanout exchange is recommended for better flexibility, allowing consumers to filter events (e.g., by status or region)[cite: 50].

### 2. Consumer Application (Order Service)
* [cite_start]**Connectivity:** Connect to RabbitMQ and filter for events where the status is "new"[cite: 39].
* **Actions upon Receipt:**
    1.  [cite_start]**Calculate Shipping:** `shippingCost = 2% of totalAmount`[cite: 41].
    2.  [cite_start]**Persistence:** Log the details and store the order (including shipping cost) in an in-memory database[cite: 42, 43].
* [cite_start]**API Endpoint (`/order-details`):** Receives an `orderId` and returns the order details and calculated shipping costs[cite: 44, 45].

> **Design Tip:** The consumer should declare its own queue and bind it to the exchange. [cite_start]This ensures the queue exists before the consumer attempts to listen[cite: 52].

## Submission Deliverables
1.  [cite_start]`docker-compose.yml` for the Producer[cite: 47].
2.  [cite_start]`docker-compose.yml` for the Consumer[cite: 47].
3.  [cite_start]`README.md` answering the course-specific questions (Full name, ID, URLs, Exchange choice, Binding keys, and declaration logic)[cite: 48, 52].

## Important Instructions
* [cite_start]**Error Handling:** Return appropriate HTTP error codes (e.g., 500) with descriptive messages for failures[cite: 54].
* [cite_start]**Due Date:** 15.12.24[cite: 58].