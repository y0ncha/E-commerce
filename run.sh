#!/bin/bash

echo "========================================"
echo "Starting E-commerce System"
echo "========================================"
echo ""

echo "Starting Producer (with Kafka)..."
cd producer/
docker compose up -d
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start producer"
    exit 1
fi
echo "✓ Producer started successfully!"
echo ""

cd ..

echo "Waiting for Kafka to be ready (5 seconds)..."
sleep 5
echo ""

echo "Starting Consumer..."
cd consumer
docker compose up -d
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start consumer"
    echo "  Make sure producer is running first!"
    exit 1
fi
echo "✓ Consumer started successfully!"
echo ""

echo "========================================"
echo "System Started!"
echo "========================================"
echo ""
echo "Services:"
echo "  - Producer API:  http://localhost:8081/cart-service/health/live"
echo "  - Consumer API:  http://localhost:8082/order-service/health/live"
echo "  - Kafka UI:      http://localhost:8080"
echo ""

