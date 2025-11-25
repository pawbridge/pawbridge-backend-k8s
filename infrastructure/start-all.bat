@echo off
echo ========================================
echo PawBridge Infrastructure Startup
echo ========================================
echo.

echo [1/4] Creating Docker network...
docker network create pawbridge-network 2>nul
if %errorlevel% equ 0 (
    echo Network created successfully!
) else (
    echo Network already exists or creation skipped.
)
echo.

echo [2/4] Starting MySQL...
cd mysql && docker-compose up -d && cd ..
if %errorlevel% neq 0 (
    echo ERROR: Failed to start MySQL
    exit /b 1
)
echo MySQL started successfully!
echo.

echo [3/4] Starting Kafka...
cd kafka && docker-compose up -d && cd ..
if %errorlevel% neq 0 (
    echo ERROR: Failed to start Kafka
    exit /b 1
)
echo Kafka started successfully!
echo.

echo [4/4] Starting Redis...
cd redis && docker-compose up -d && cd ..
if %errorlevel% neq 0 (
    echo ERROR: Failed to start Redis
    exit /b 1
)
echo Redis started successfully!
echo.

echo ========================================
echo All infrastructure services started!
echo ========================================
echo.
docker ps
