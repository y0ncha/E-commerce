# Error Handling

## Global Exception Handling
- Centralized in `GlobalExceptionHandler`.
- Returns JSON with clear messages and appropriate HTTP status codes.

### Validation Errors (400)
- **MethodArgumentNotValidException**: Bean validation failures (missing/invalid fields).
- **InvalidOrderIdException**: orderId not hex or blank.
- **HttpMessageNotReadableException**: malformed JSON, missing body, wrong types, unknown fields.

### Business Errors
- **OrderNotFoundException** (404): Order does not exist (update).
- **DuplicateOrderException** (409): Order already exists (create).

### Kafka Errors (500)
- **ProducerSendException**: Send failed after retries/timeouts; includes type (TIMEOUT, INTERRUPTED, KAFKA_ERROR, UNEXPECTED) and orderId.

### Fallback Errors (500)
- Any unhandled exception → 500 Internal Server Error with generic message.

## Kafka Send Path and Retries
- Send is synchronous: `kafkaTemplate.send(...).get(timeout)` (10s default).
- Retries are handled by Kafka client per configuration (no manual loops):
  - `acks=all`, `enable.idempotence=true` → safe retries without duplicates.
  - `retries=3`, `retry.backoff.ms=100` → transient failure recovery.
  - `max.in.flight.requests.per.connection=1` → preserves ordering with retries.
  - `request.timeout.ms=5000`, `delivery.timeout.ms=120000` → bound wait time; timeouts surface as errors.
- On exhausted retries/timeout, a 500 is returned with `ProducerSendException` details.

## Health Endpoints
- **Liveness** (`GET /cart-service/health/live`): checks service only; always 200 with service status.
- **Readiness** (`GET /cart-service/health/ready`): checks service + Kafka; 200 if Kafka UP, else 503. Response includes per-check status/details.

## Error Response Examples
- 400 Validation: `{ "message": "Validation error", "errors": { "numItems": "numItems is required" } }`
- 400 Malformed JSON: `{ "message": "Malformed JSON syntax" }`
- 404 Not Found: `{ "message": "Order ORD-123 not found" }`
- 409 Conflict: `{ "message": "Order ORD-123 already exists" }`
- 500 Kafka Error: `{ "error": "Failed to publish message", "type": "TIMEOUT", "orderId": "ORD-123" }`

