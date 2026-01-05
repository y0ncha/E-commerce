@echo off
echo ========================================
echo Starting E-commerce System
echo ========================================
echo.

echo Starting Producer (with Kafka)...
cd producer
docker compose up -d
if errorlevel 1 (
    echo ERROR: Failed to start producer
    pause
    exit /b 1
)
echo Producer started successfully!
echo.

cd ..

echo Waiting for Kafka to be ready (5 seconds)...
timeout /t 5 /nobreak >nul
echo.

echo Starting Consumer...
cd consumer
docker compose up -d
if errorlevel 1 (
    echo ERROR: Failed to start consumer
    pause
    exit /b 1
)
echo Consumer started successfully!
echo.

echo ========================================
echo System Started!
echo ========================================
echo.
echo Services:
echo   - Producer API:  http://localhost:8081/cart-service/health/live
echo   - Consumer API:  http://localhost:8082/order-service/health/live
echo   - Kafka UI:      http://localhost:8080
echo.
pause

