#!/bin/bash
# Build script for EDA Producer v2.1.0 - Kafka Cluster Edition
# Builds multi-platform Docker image (amd64, arm64) using docker buildx
# v2.x = Kafka variants | v1.x = RabbitMQ variants

set -e

# Change to producer directory (where Dockerfile is located)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCER_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PRODUCER_DIR"

echo "🐳 Building EDA Producer v2.1.0 (Kafka Cluster Edition)..."
echo "📂 Build context: $PRODUCER_DIR"
echo ""
echo "Platforms: linux/amd64, linux/arm64"
echo "Tags:"
echo "  - y0ncha/eda-producer:2.1.0"
echo ""

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t y0ncha/eda-producer:2.1.0 \
  --provenance=false \
  --sbom=false \
  --push \
  .

echo ""
echo "✅ Build completed and pushed successfully!"
echo ""
echo "Image: y0ncha/eda-producer:2.1.0"
echo ""
echo "Run with cluster:"
echo "  cd .."
echo "  docker-compose up -d"
echo ""
echo "Test:"
echo "  curl http://localhost:8080/cart-service"
echo "  curl http://localhost:8080/cart-service/health/ready"

