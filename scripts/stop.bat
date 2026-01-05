@echo off
REM Get the directory where this script is located and navigate to project root
cd /d "%~dp0.."

echo ========================================
echo Stopping E-commerce System
echo ========================================
echo.

echo Stopping Consumer...
cd consumer
docker compose down
if errorlevel 1 (
    echo ERROR: Failed to stop consumer
) else (
    echo Consumer stopped
)
echo.

cd ..

echo Stopping Producer (and Kafka)...
cd producer
docker compose down
if errorlevel 1 (
    echo ERROR: Failed to stop producer
) else (
    echo Producer stopped
)
echo.

echo ========================================
echo System Stopped!
echo ========================================
pause

