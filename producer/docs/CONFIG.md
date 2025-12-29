# Configuration Overview

## Kafka Connectivity
- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- Docker profile: use `KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `SPRING_PROFILES_ACTIVE=docker`.
- Kafka admin: `spring.kafka.admin.fail-fast=false`, request/default API timeouts 5s.

## Topic
- `kafka.topic.name=order-events`
- Partitions: 3 (configurable), replication: 1 (dev default).

## Producer Durability & Ordering
- `acks=all`
- `enable.idempotence=true`
- `retries=3`
- `retry.backoff.ms=100`
- `max.in.flight.requests.per.connection=1` (preserve ordering with retries)
- `batch.size=16384`
- `linger.ms=0` (send immediately, no batching delay)
- `compression.type=snappy`
- `key` = `orderId` (partitioned by order; guarantees per-order ordering)
- `value` = JSON Order payload (JsonSerializer)

## Timeouts
- `request.timeout.ms=5000`
- `delivery.timeout.ms=120000`
- API send timeout: `producer.send.timeout.ms` (10s default, enforced via `send(...).get(timeout)`).

## Health
- Liveness: service only, always 200.
- Readiness: service + Kafka, 200 if Kafka reachable and topic exists, else 503.

## Profiles / Docker
- Default (IDE/local): bootstrap `localhost:9092`.
- Docker profile: `spring.profiles.active=docker`, bootstrap `kafka:9092`.

## How Retries Work
- Kafka client handles retries (no manual loops).
- Safe retries because: `acks=all`, `enable.idempotence=true`, `max.in.flight=1`.
- Backoff: 100ms between retry attempts.
- Failure surfaced as `ProducerSendException` → HTTP 500.

## Logging
- Kafka client logs set to WARN to reduce bootstrap spam.

## Paths
- Application config: `src/main/resources/application.properties`
- Producer beans: `src/main/java/mta/eda/producer/config/KafkaProducerConfig.java`
- Topic config: `src/main/java/mta/eda/producer/config/KafkaTopicConfig.java`
- Health: `src/main/java/mta/eda/producer/controller/OrderController.java`, `service/kafka/KafkaHealthService.java`

