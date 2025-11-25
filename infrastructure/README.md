# PawBridge Infrastructure

PawBridge 마이크로서비스 아키텍처의 인프라 구성 요소를 관리하는 폴더입니다.

## 개요

- **목적**: 개발 환경용 로컬 인프라 구성 (MySQL, Kafka, Redis)
- **전략**: 기술별로 독립적인 docker-compose.yml 분리
- **네트워크**: `pawbridge-network`로 모든 컨테이너 연결
- **포트**: 표준 포트 사용 (3306, 9092, 6379)

## 폴더 구조

```
infrastructure/
├── mysql/
│   ├── docker-compose.yml       # MySQL 8.0 서버
│   └── init-sql/
│       └── init.sql             # DB 초기화 스크립트
├── kafka/
│   └── docker-compose.yml       # Kafka + Zookeeper
├── redis/
│   └── docker-compose.yml       # Redis 7
├── start-all.bat                # Windows 통합 시작 스크립트
├── start-all.sh                 # Linux/Mac 통합 시작 스크립트
├── stop-all.bat                 # Windows 통합 종료 스크립트
└── stop-all.sh                  # Linux/Mac 통합 종료 스크립트
```

## 빠른 시작

### 1. 전체 인프라 시작 (권장)

**Windows:**
```bash
cd infrastructure
start-all.bat
```

**Linux/Mac:**
```bash
cd infrastructure
chmod +x start-all.sh
./start-all.sh
```

### 2. 개별 서비스 시작

```bash
# MySQL
cd infrastructure/mysql
docker-compose up -d

# Kafka
cd infrastructure/kafka
docker-compose up -d

# Redis
cd infrastructure/redis
docker-compose up -d
```

### 3. 전체 인프라 종료

**Windows:**
```bash
cd infrastructure
stop-all.bat
```

**Linux/Mac:**
```bash
cd infrastructure
./stop-all.sh
```

## 서비스 상세

### MySQL (db-server)

- **포트**: 3306
- **컨테이너명**: db-server
- **Root 계정**: root / root
- **애플리케이션 계정**: app_client / app_password

**생성되는 데이터베이스:**
- `pawbridge_user` - 사용자 서비스
- `pawbridge_animal` - 동물 서비스
- `pawbridge_community` - 커뮤니티 서비스
- `pawbridge_store` - 상점 서비스
- `pawbridge_payment` - 결제 서비스

**접속 확인:**
```bash
docker exec -it db-server mysql -uroot -proot
SHOW DATABASES;
```

### Kafka + Zookeeper

- **Kafka 포트**: 9092
- **Zookeeper 포트**: 2181
- **Kafka 컨테이너명**: kafka-broker
- **Zookeeper 컨테이너명**: zookeeper
- **토픽 자동 생성**: 활성화

**토픽 확인:**
```bash
docker exec -it kafka-broker kafka-topics --bootstrap-server localhost:9092 --list
```

### Redis (redis-cache)

- **포트**: 6379
- **컨테이너명**: redis-cache
- **영속성**: AOF 활성화

**접속 확인:**
```bash
docker exec -it redis-cache redis-cli
PING
```

## 애플리케이션 연결 설정

각 서비스의 `application-local.yml`에 다음 설정이 적용되어 있습니다:

### animal-service
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/pawbridge_animal
    username: app_client
    password: app_password
```

### user-service
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/pawbridge_user
    username: app_client
    password: app_password
```

**서비스 실행 시 프로파일 지정:**
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

**IntelliJ Run Configuration:**
- Active profiles: `local`
- 환경 변수 설정은 `ENV_SETUP_GUIDE.md` 참고

## 문제 해결

### 포트 충돌
기존에 실행 중인 MySQL, Kafka, Redis가 있다면 중지 후 재시작:
```bash
# 기존 컨테이너 확인
docker ps

# 충돌하는 컨테이너 중지
docker stop <container-name>
```

### 네트워크 에러
`pawbridge-network`가 없으면 자동 생성되지만, 수동 생성도 가능:
```bash
docker network create pawbridge-network
```

### 볼륨 초기화
데이터를 완전히 삭제하고 재시작:
```bash
cd infrastructure/mysql
docker-compose down -v
docker-compose up -d
```

### 로그 확인
```bash
# 전체 로그
docker-compose logs -f

# 특정 서비스 로그
docker logs -f db-server
docker logs -f kafka-broker
docker logs -f redis-cache
```

## 데이터 마이그레이션

기존 docker-compose.yml에서 데이터를 마이그레이션하려면:

```bash
# 1. 기존 데이터 백업
docker exec pawbridge-animal-mysql mysqldump -uroot -proot pawbridge_animal > animal_backup.sql
docker exec user-mysql mysqldump -uroot -proot userdb > user_backup.sql

# 2. 새 인프라 시작
cd infrastructure
./start-all.bat  # Windows
./start-all.sh   # Linux/Mac

# 3. 데이터 복원
docker exec -i db-server mysql -uapp_client -papp_password pawbridge_animal < animal_backup.sql
docker exec -i db-server mysql -uapp_client -papp_password pawbridge_user < user_backup.sql
```

## 운영 환경

운영 환경에서는 관리형 서비스 사용을 권장합니다:

- **MySQL**: AWS RDS for MySQL (Multi-AZ)
- **Kafka**: AWS MSK (Managed Streaming for Kafka)
- **Redis**: AWS ElastiCache for Redis
