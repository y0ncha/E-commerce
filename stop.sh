#!/bin/bash

cd ..

echo "Removing shared Docker network..."
docker network rm ecommerce-network 2>/dev/null
if [ $? -eq 0 ]; then
    echo "✓ Network 'ecommerce-network' removed"
else
    echo "✓ Network 'ecommerce-network' not found or already removed"
fi
echo ""

echo "========================================"
echo "Stopping E-commerce System"
echo "========================================"
echo ""

echo "Stopping Consumer..."
cd consumer
docker compose down
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to stop consumer"
else
    echo "✓ Consumer stopped"
fi
echo ""

cd ..

echo "Stopping Producer (and Kafka)..."
cd producer
docker compose down
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to stop producer"
else
    echo "✓ Producer stopped"
fi
echo ""

