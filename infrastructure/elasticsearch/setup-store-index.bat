@echo off
chcp 65001 > nul
setlocal

:: 설정 변수
set ELASTICSEARCH_URL=http://localhost:9200
set INDEX_NAME=store.outbox.events
set MAPPING_FILE=%~dp0mappings\store-index-mapping.json

echo [INFO] Store Service Elasticsearch Index Setup Script
echo [INFO] URL: %ELASTICSEARCH_URL%
echo [INFO] Index: %INDEX_NAME%
echo [INFO] Mapping File: %MAPPING_FILE%
echo.

:: 1. Elasticsearch 연결 확인
echo [CHECK] Connecting to Elasticsearch...
curl -s -f "%ELASTICSEARCH_URL%" > nul
if errorlevel 1 (
    echo [ERROR] Cannot connect to Elasticsearch at %ELASTICSEARCH_URL%
    echo Please make sure Elasticsearch is running.
    goto :EOF
)
echo [OK] Connected.
echo.

:: 2. 기존 인덱스 확인 및 삭제
echo [CHECK] Checking for existing index...
curl -s -f "%ELASTICSEARCH_URL%/%INDEX_NAME%" > nul
if not errorlevel 1 (
    echo [INFO] Existing index '%INDEX_NAME%' found.
    echo [ACTION] Deleting existing index...
    curl -X DELETE "%ELASTICSEARCH_URL%/%INDEX_NAME%"
    echo.
    echo [OK] Deleted.
) else (
    echo [INFO] No existing index found.
)
echo.

:: 3. 새 인덱스 생성
echo [ACTION] Creating new index '%INDEX_NAME%'...
if exist "%MAPPING_FILE%" (
    curl -X PUT "%ELASTICSEARCH_URL%/%INDEX_NAME%" -H "Content-Type: application/json" -d @%MAPPING_FILE%
    echo.
    echo.
    echo [OK] Index created successfully with custom mapping.
) else (
    echo [ERROR] Mapping file not found: %MAPPING_FILE%
    goto :EOF
)

echo.
echo [DONE] Setup completed.
endlocal