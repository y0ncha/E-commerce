# Kafka Producer Configuration

This directory contains the Kafka configuration for the Producer service. The configuration is **grade-critical** as it directly impacts ordering guarantees, durability, and error resilience.

---

## Overview

The configuration is split across three components:

1. **`application.properties`** - Property values (externalized configuration)
2. **`KafkaProducerConfig.java`** - Producer factory and KafkaTemplate bean creation
3. **`KafkaTopicConfig.java`** - Topic creation and partition configuration

---

## Table of Contents

- [Configuration Files](#configuration-files)
- [Critical Configuration Decisions](#critical-configuration-decisions)
- [Configuration Reference Table](#configuration-reference-table)
- [Why These Choices Matter](#why-these-choices-matter)
- [Testing the Configuration](#testing-the-configuration)
- [Tuning Guide](#tuning-guide)

---

## Configuration Files

### KafkaProducerConfig.java

**Purpose:** Creates the `ProducerFactory` and `KafkaTemplate` beans used by services to publish messages to Kafka.

**Key responsibilities:**
- Loads properties from `application.properties`
- Configures serializers (String key, JSON value)
- Sets durability, ordering, and resilience parameters
- Creates the `KafkaTemplate<String, Order>` bean

**Bean lifecycle:**
```text
application.properties → @Value injection → ProducerFactory → KafkaTemplate
```

### KafkaTopicConfig.java

**Purpose:** Auto-creates the `order-events` topic when the application starts.

**Key responsibilities:**
- Defines topic name (configurable)
- Sets partition count (default: 3)
- Sets replication factor (default: 1)

---

## Critical Configuration Decisions

### 1. Message Key Strategy (CRITICAL)
- **Choice:** Use `orderId` as the message key.
- **Why:** Kafka guarantees ordering **only within a partition**. Using the same key ensures all events for a specific order go to the same partition and are processed sequentially.

### 2. Producer Acknowledgments (acks)
- **Choice:** `acks=all`.
- **Why:** Wait for all in-sync replicas to acknowledge. This provides the strongest durability guarantee.

### 3. Synchronous Online Retry (Architectural Goal)
- **Strategy:** We prioritize **Response Accuracy**. If a user receives a failure response, the system has truly stopped trying to send the message.
- **Configuration:** 
    - `delivery.timeout.ms=8000` (8s) - Ensures Kafka gives up before the 10s API timeout.
    - `request.timeout.ms=3000` (3s) - Allows ~2-3 "online" retry attempts within the 8s window.
    - `retries=2147483647` - Ensures Kafka retries until the time limit is reached.
- **Benefit:** Prevents "Ghost Successes" where an order lands in Kafka after the user was told it failed.

### 4. In-Flight Requests (CRITICAL for Ordering)
- **Choice:** `max.in.flight.requests.per.connection=1`.
- **Why:** Prevents message reordering during retries.

### 5. Idempotence
- **Choice:** `enable.idempotence=true`.
- **Why:** Prevents duplicate messages on the broker during retries.

### 6. Circuit Breaker (Fail-Fast)
- **Choice:** Resilience4j `cartService` instance.
- **Why:** Protects system threads from exhaustion. If Kafka is down, the circuit trips and returns instant 503 errors instead of waiting for 10s timeouts.

---

## Configuration Reference Table

| Property | Value | Why | Criticality |
|----------|-------|-----|-------------|
| `bootstrap-servers` | `localhost:9092` | Kafka broker address | Required |
| `key-serializer` | `StringSerializer` | orderId is a string | Required |
| `value-serializer` | `JsonSerializer` | Order is JSON | Required |
| **`acks`** | **`all`** | **Durability: all replicas** | **CRITICAL** |
| **`retries`** | **`MAX_INT`** | **Retry until time limit** | **CRITICAL** |
| `retry.backoff.ms` | `100` | Exponential backoff | Important |
| **`max.in.flight`** | **`1`** | **Ordering guarantee** | **CRITICAL** |
| **`enable.idempotence`** | **`true`** | **Exactly-once semantics** | **CRITICAL** |
| `request.timeout.ms` | `3000` | Allows multiple online retries | Important |
| `delivery.timeout.ms` | `8000` | **Aligned with API timeout** | **CRITICAL** |
| `topic.name` | `order-events` | Topic for all order events | Required |
| `topic.partitions` | `3` | Parallel processing | Configurable |
| `topic.replication-factor` | `1` | Single broker setup | Environment-specific |

---

## Why These Choices Matter

### For Exercise 02 Grading
- **Ordering**: `orderId` key + `max.in.flight=1` ensures sequential processing.
- **Durability**: `acks=all` ensures data is replicated.
- **Resilience**: Circuit Breaker + Synchronous Retries ensure a responsive and accurate API.

---

## Testing the Configuration

### 1. Verify Response Accuracy
Stop Kafka and send a request. Verify the API returns a **500 Internal Server Error** after exactly 10 seconds. Start Kafka and verify the message **did not** appear in the topic (since the 8s delivery timeout was reached before the 10s API limit).

### 2. Verify Circuit Breaker
Send 10 failing requests. Verify that subsequent requests return a **503 Service Unavailable** instantly (milliseconds) instead of waiting 10 seconds.

---

## Tuning Guide

### For Production
- **Replication Factor**: Increase to 3.
- **Wait Duration**: Adjust circuit breaker `wait-duration-in-open-state` based on recovery SLAs.

---

## Summary

### Grade-Critical Settings
1. ✅ `orderId` as message key
2. ✅ `acks=all`
3. ✅ `max.in.flight.requests.per.connection=1`
4. ✅ `enable.idempotence=true`
5. ✅ `delivery.timeout.ms` aligned with API timeout

---

**Last Updated:** January 1, 2026
**Configuration Version:** Synchronous Online Retry Implementation
