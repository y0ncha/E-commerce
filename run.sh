#!/bin/bash

echo "========================================"
echo "Starting E-commerce System"
echo "========================================"
echo ""

echo "Creating shared Docker network..."
docker network create ecommerce-network 2>/dev/null || true
if [ "$(docker network ls -q -f name=ecommerce-network)" ]; then
    echo "✓ Network 'ecommerce-network' ready!"
else
    echo "✗ Failed to create network"
    exit 1
fi
echo ""

echo "Starting Producer (with Kafka)..."
cd producer
docker compose up -d
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start producer"
    exit 1
fi
echo "✓ Producer started successfully!"
echo ""

cd ..

echo "Starting Consumer..."
cd consumer
docker compose up -d
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start consumer"
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
echo "Note: Services can start in any order."
echo "      Network 'ecommerce-network' created automatically."
echo "      Consumer will auto-connect when Kafka is ready."
echo ""

