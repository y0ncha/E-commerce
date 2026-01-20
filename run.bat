@echo off
setlocal

echo ========================================
echo Starting E-commerce System
echo ========================================
echo.

rem Check docker is available
where docker >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: 'docker' was not found in PATH. Please install Docker or add it to PATH.
    pause
    exit /b 1
)

echo Starting Producer (with Kafka)...
pushd "%~dp0producer"
docker compose up -d
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to start producer (docker compose up failed)
    popd
    pause
    exit /b 1
)
echo Producer started successfully!
popd
echo.

echo Waiting for Kafka to be ready (5 seconds)...
timeout /t 5 /nobreak >nul
echo.

echo Starting Consumer...
pushd "%~dp0consumer"
docker compose up -d
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to start consumer (docker compose up failed)
    popd
    pause
    exit /b 1
)
echo Consumer started successfully!
popd
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
endlocal
