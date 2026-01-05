@echo off
echo ========================================
echo Starting E-commerce System
echo ========================================
echo.

echo Creating shared Docker network...
docker network create ecommerce-network >nul 2>&1
if "%ERRORLEVEL%"=="0" (
    echo Network 'ecommerce-network' created!
) else (
    docker network ls | findstr "ecommerce-network" >nul
    if "%ERRORLEVEL%"=="0" (
        echo Network 'ecommerce-network' already exists!
    ) else (
        echo ERROR: Failed to create network
        pause
        exit /b 1
    )
)
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
echo.
pause
