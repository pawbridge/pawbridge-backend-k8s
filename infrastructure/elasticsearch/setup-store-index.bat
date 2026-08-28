@echo off
chcp 65001 > nul
setlocal enabledelayedexpansion

:: 기존 인덱스를 삭제하지 않고 새 버전 인덱스만 생성한다.
set "ELASTICSEARCH_URL=%~1"
set "INDEX_NAME=%~2"
set "MAPPING_FILE=%~dp0mappings\store-index-mapping.json"

if "%ELASTICSEARCH_URL%"=="" goto :USAGE
if "%INDEX_NAME%"=="" goto :USAGE
echo(!INDEX_NAME!| findstr /R /X "store-products-v[0-9][0-9]*" > nul
if errorlevel 1 (
    echo [ERROR] Unexpected index name: %INDEX_NAME%
    exit /b 2
)

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
    exit /b 1
)
echo [OK] Connected.
echo.

:: 2. 기존 인덱스가 있으면 덮어쓰지 않는다.
echo [CHECK] Checking for existing index...
curl -s -f "%ELASTICSEARCH_URL%/%INDEX_NAME%" > nul
if not errorlevel 1 (
    echo [ERROR] Existing index '%INDEX_NAME%' found. Refusing to overwrite.
    exit /b 2
)
echo [OK] Target index does not exist.
echo.

:: 3. 새 인덱스 생성
echo [ACTION] Creating new index '%INDEX_NAME%'...
if exist "%MAPPING_FILE%" (
    set RESULT_FILE=%TEMP%\pawbridge-store-index-%RANDOM%.json
    curl -sS -f -X PUT "%ELASTICSEARCH_URL%/%INDEX_NAME%" -H "Content-Type: application/json" -d @%MAPPING_FILE% -o "!RESULT_FILE!"
    if errorlevel 1 (
        echo [ERROR] Index creation request failed.
        if exist "!RESULT_FILE!" type "!RESULT_FILE!"
        if exist "!RESULT_FILE!" del "!RESULT_FILE!"
        exit /b 1
    )
    findstr /C:"\"acknowledged\":true" "!RESULT_FILE!" > nul
    if errorlevel 1 (
        echo [ERROR] Elasticsearch did not acknowledge index creation.
        type "!RESULT_FILE!"
        del "!RESULT_FILE!"
        exit /b 1
    )
    del "!RESULT_FILE!"
    echo [OK] Versioned index created. Aliases were not changed.
) else (
    echo [ERROR] Mapping file not found: %MAPPING_FILE%
    exit /b 1
)

echo.
echo [DONE] Setup completed.
endlocal
exit /b 0

:USAGE
echo Usage: %~nx0 ^<elasticsearch-url^> ^<new-version-index^>
echo Example: %~nx0 http://localhost:9200 store-products-v001
exit /b 2
