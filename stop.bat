@echo off
setlocal

echo ========================================
echo Stopping E-commerce System
echo ========================================
echo.

rem Check docker is available
where docker >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo WARNING: 'docker' was not found in PATH. Cannot stop services via docker compose.
    pause
    exit /b 1
)

echo Stopping Consumer...
pushd "%~dp0consumer"
docker compose down
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to stop consumer
) else (
    echo Consumer stopped
)
popd
echo.

echo Stopping Producer (and Kafka)...
pushd "%~dp0producer"
docker compose down
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to stop producer
) else (
    echo Producer stopped
)
popd
echo.

echo ========================================
echo System Stopped!
echo ========================================
pause
endlocal
