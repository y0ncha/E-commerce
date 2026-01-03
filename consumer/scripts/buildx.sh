#!/bin/bash
# Build script for EDA consumer v2.0.0 - Kafka Single Broker Edition
# Builds multi-platform Docker image (amd64, arm64) using docker buildx
# v2.x = Kafka variants | v1.x = RabbitMQ variants

set -e

# Change to consumer directory (where Dockerfile is located)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
consumer_DIR="$(dirname "$SCRIPT_DIR")"
cd "$consumer_DIR"

echo "🐳 Building EDA consumer v2.0.0 (Kafka Single Broker Edition)..."
echo "📂 Build context: $consumer_DIR"
echo ""
echo "Platforms: linux/amd64, linux/arm64"
echo "Tags:"
echo "  - y0ncha/eda-consumer:2.0.0"
echo ""

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t y0ncha/eda-consumer:2.0.0 \
  --provenance=false \
  --sbom=false \
  --push \
  .

echo ""
echo "Build completed and pushed successfully! ✅"
echo ""
echo "Image: y0ncha/eda-consumer:2.0.0"
echo ""
echo "Run with single broker:"
echo "  docker run -d \\"
echo "    -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \\"
echo "    -p 8082:8082 \\"
echo "    y0ncha/eda-consumer:2.0.0"
echo ""
echo "Test:"
echo "  curl http://localhost:8080/order-service"
echo "  curl http://localhost:8080/order-service/health/ready"

