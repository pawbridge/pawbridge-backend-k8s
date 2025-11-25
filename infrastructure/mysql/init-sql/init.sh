#!/bin/bash
# PawBridge 마이크로서비스 데이터베이스 초기화 스크립트
# 서비스별 논리적 DB 분리 (스키마 분리 전략)
# AWS 비용 절감을 위해 단일 MySQL 인스턴스에서 스키마로 분리

set -e

echo "Initializing PawBridge databases..."

# 환경 변수 기본값 설정
MYSQL_APP_USER=${MYSQL_APP_USER:-app_client}
MYSQL_APP_PASSWORD=${MYSQL_APP_PASSWORD:-changeme}

# MySQL 접속
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    -- 서비스별 데이터베이스 생성
    CREATE DATABASE IF NOT EXISTS pawbridge_user CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS pawbridge_animal CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS pawbridge_community CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS pawbridge_store CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS pawbridge_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

    -- 애플리케이션 공용 계정 생성
    CREATE USER IF NOT EXISTS '${MYSQL_APP_USER}'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD}';

    -- 권한 부여
    GRANT ALL PRIVILEGES ON pawbridge_user.* TO '${MYSQL_APP_USER}'@'%';
    GRANT ALL PRIVILEGES ON pawbridge_animal.* TO '${MYSQL_APP_USER}'@'%';
    GRANT ALL PRIVILEGES ON pawbridge_community.* TO '${MYSQL_APP_USER}'@'%';
    GRANT ALL PRIVILEGES ON pawbridge_store.* TO '${MYSQL_APP_USER}'@'%';
    GRANT ALL PRIVILEGES ON pawbridge_payment.* TO '${MYSQL_APP_USER}'@'%';

    FLUSH PRIVILEGES;

    -- 초기화 완료 확인
    SELECT 'Database initialization completed successfully' AS status;
EOSQL

echo "Database initialization completed!"
