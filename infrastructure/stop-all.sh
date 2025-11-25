#!/bin/bash

echo "========================================"
echo "PawBridge Infrastructure Shutdown"
echo "========================================"
echo ""

echo "[1/3] Stopping Redis..."
cd redis && docker-compose down && cd ..
echo "Redis stopped!"
echo ""

echo "[2/3] Stopping Kafka..."
cd kafka && docker-compose down && cd ..
echo "Kafka stopped!"
echo ""

echo "[3/3] Stopping MySQL..."
cd mysql && docker-compose down && cd ..
echo "MySQL stopped!"
echo ""

echo "========================================"
echo "All infrastructure services stopped!"
echo "========================================"
echo ""
docker ps
