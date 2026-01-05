#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Navigate to the project root (parent of scripts directory)
cd "$SCRIPT_DIR/.."

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

echo "========================================"
echo "System Stopped!"
echo "========================================"

