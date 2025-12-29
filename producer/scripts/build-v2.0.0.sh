#!/bin/bash
# Build script for EDA Producer v2.0.0 - Kafka Single Broker Edition
# Builds multi-platform Docker image (amd64, arm64) using docker buildx
# v2.x = Kafka variants | v1.x = RabbitMQ variants

set -e

# Change to producer directory (where Dockerfile is located)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCER_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PRODUCER_DIR"

echo "🐳 Building EDA Producer v2.0.0 (Kafka Single Broker Edition)..."
echo "📂 Build context: $PRODUCER_DIR"
echo ""
echo "Platforms: linux/amd64, linux/arm64"
echo "Tags:"
echo "  - y0ncha/eda-producer:2.0.0"
echo ""

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t y0ncha/eda-producer:2.0.0 \
  --provenance=false \
  --sbom=false \
  --push \
  .

echo ""
echo "✅ Build completed and pushed successfully!"
echo ""
echo "Image: y0ncha/eda-producer:2.0.0"
echo ""
echo "Run with single broker:"
echo "  docker run -d \\"
echo "    -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \\"
echo "    -p 8080:8080 \\"
echo "    y0ncha/eda-producer:2.0.0"
echo ""
echo "Test:"
echo "  curl http://localhost:8080/cart-service"
echo "  curl http://localhost:8080/cart-service/health/ready"

