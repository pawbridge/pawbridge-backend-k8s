#!/bin/bash

echo "========================================"
echo "PawBridge Infrastructure Startup"
echo "========================================"
echo ""

echo "[1/4] Creating Docker network..."
docker network create pawbridge-network 2>/dev/null
if [ $? -eq 0 ]; then
    echo "Network created successfully!"
else
    echo "Network already exists or creation skipped."
fi
echo ""

echo "[2/4] Starting MySQL..."
cd mysql && docker-compose up -d && cd ..
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start MySQL"
    exit 1
fi
echo "MySQL started successfully!"
echo ""

echo "[3/4] Starting Kafka..."
cd kafka && docker-compose up -d && cd ..
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start Kafka"
    exit 1
fi
echo "Kafka started successfully!"
echo ""

echo "[4/4] Starting Redis..."
cd redis && docker-compose up -d && cd ..
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to start Redis"
    exit 1
fi
echo "Redis started successfully!"
echo ""

echo "========================================"
echo "All infrastructure services started!"
echo "========================================"
echo ""
docker ps
