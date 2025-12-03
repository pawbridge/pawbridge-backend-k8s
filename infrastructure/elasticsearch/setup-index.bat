@echo off
setlocal enabledelayedexpansion

REM Elasticsearch animals 인덱스 설정 스크립트 (Windows)
REM - Nori Analyzer 설정 적용
REM - 인덱스 매핑 설정

set ELASTICSEARCH_URL=http://localhost:9200
set INDEX_NAME=animals
set MAPPING_FILE=.\mappings\animals-index-mapping.json

echo ================================================
echo Elasticsearch 인덱스 설정 시작...
echo    - URL: %ELASTICSEARCH_URL%
echo    - Index: %INDEX_NAME%
echo ================================================
echo.

REM 1. Elasticsearch 연결 확인
echo [1/3] Elasticsearch 연결 확인 중...
curl -s %ELASTICSEARCH_URL% >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Elasticsearch 연결 실패: %ELASTICSEARCH_URL%
    echo         Elasticsearch가 실행 중인지 확인하세요.
    exit /b 1
)
echo [OK] Elasticsearch 연결 성공
echo.

REM 2. 기존 인덱스 확인 및 삭제
echo [2/3] 기존 인덱스 확인 중...
curl -s -f %ELASTICSEARCH_URL%/%INDEX_NAME% >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [WARNING] 기존 인덱스 발견: %INDEX_NAME%
    echo           기존 인덱스를 삭제하고 재생성합니다...
    curl -s -X DELETE %ELASTICSEARCH_URL%/%INDEX_NAME%
    echo [OK] 기존 인덱스 삭제 완료
) else (
    echo [INFO] 기존 인덱스 없음
)
echo.

REM 3. 새 인덱스 생성
echo [3/3] 새 인덱스 생성 중...
echo       Mapping file: %MAPPING_FILE%

curl -s -X PUT %ELASTICSEARCH_URL%/%INDEX_NAME% ^
    -H "Content-Type: application/json" ^
    -d @%MAPPING_FILE% > temp_result.json

findstr /C:"acknowledged" temp_result.json >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [SUCCESS] 인덱스 생성 성공!
    echo.
    type temp_result.json
    del temp_result.json
) else (
    echo [ERROR] 인덱스 생성 실패
    type temp_result.json
    del temp_result.json
    exit /b 1
)

echo.
echo ================================================
echo 인덱스 설정 완료!
echo ================================================
