@echo off
setlocal
set DOCKER_ID=dorosiya
set VERSION=k8s-v1

echo ========================================================
echo   PawBridge Docker Image Build ^& Push Script
echo   Docker ID: %DOCKER_ID%
echo   Version:   %VERSION%
echo ========================================================

:: 1. Build and Push Each Service (Gradle Build inside loop)

:: 2. Build and Push Each Service
set SERVICES=user-service animal-service community-service store-service payment-service api-gateway

for %%s in (%SERVICES%) do (
    echo.
    echo ========================================================
    echo   Processing %%s...
    echo ========================================================
    
    cd %%s
    
    echo [Build] Building JAR with Gradle...
    call gradlew clean build -x test
    if errorlevel 1 (
        echo Gradle Build Failed for %%s!
        cd ..
        exit /b %errorlevel%
    )
    
    echo [Build] Building Docker Image...
    docker build -t %DOCKER_ID%/pawbridge-%%s:%VERSION% .
    if errorlevel 1 (
        echo Docker Build Failed for %%s!
        cd ..
        exit /b %errorlevel%
    )
    
    echo [Push] Pushing to Docker Hub...
    docker push %DOCKER_ID%/pawbridge-%%s:%VERSION%
    if errorlevel 1 (
        echo Docker Push Failed for %%s!
        cd ..
        exit /b %errorlevel%
    )
    
    cd ..
)

echo.
echo ========================================================
echo   All Images Built and Pushed Successfully!
echo ========================================================
pause
