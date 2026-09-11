# Payment 스키마 도구 실행

## 검증 실행 기준

| 명령 | 실행 시점 | 범위 |
| --- | --- | --- |
| `migrationTest` | 일반 `check`에 포함 | 우리 실행 도구의 입력·대상·안전 설정, 15개 |
| `migrationMysqlTest` | SQL·매핑·실행 도구 변경 시 격리 DB에서 명시 실행 | 실제 V1/JPA 일치, SQL 누락·기존 DB 자동 편입 거절, 3개 |
| `migrationRehearsal` | 초기 편입 또는 baseline 절차 변경 시 명시 실행 | 기존 행을 보존하는 baseline 리허설, 1개 |

`migrationRehearsal`은 `check`와 `migrationMysqlTest`에 포함되지 않는다. 같은 전용 DB 준비와 서비스별 `*_MIGRATION_TEST_PORT`가 필요하다.
이미지 패키징·백업 복원 시험은 인프라 저장소의 수동 리허설이며 일반 빌드에 연결하지 않는다.
Flyway 자체의 체크섬·중복 실행을 반복 검증하던 테스트 2개와 전용 fixture를 제거했다. 아래 초기 검증 기록의 21개는 정리 이전 결과다.

2026-09-11 정리 후 세 명령을 각각 재실행해 단위 15개·SQL 검증 3개·편입 리허설 1개가 실패와 건너뛰기 없이 통과했다. 운영 변경과 전체 애플리케이션 회귀 테스트는 실행하지 않았다.

명령은 Java 17에서 `payment-service` 디렉터리를 기준으로 실행한다.

## 현재 범위

V1은 2026-09-11 운영 MySQL 8.4.12의 `pawbridge_payment` 테이블 2개를 읽기 전용으로 확인해 작성했다. 뷰·트리거·루틴·이벤트는 없었다. 운영 행과 자동증가 카운터는 포함하지 않는다. 기존 문자셋과 제약조건을 보존한다.

SQL·실행 파일·이력 테이블은 이 서비스 소유다. API 시작 시 실행하지 않으며 Hibernate 설정도 아직 바꾸지 않는다. 운영 baseline·계정 권한·배포 Job은 별도 승인 후 연결한다.

## 빌드와 조회

```bash
bash ./gradlew --no-daemon migrationTest migrationDistribution bootJar
java -cp 'build/migration/lib/*' com.pawbridge.paymentservice.migration.PaymentSchemaMigration info
```

다음 환경변수를 승인된 환경에서 주입한다. 운영 비밀번호를 명령 인자나 파일에 적지 않는다.

- `PAYMENT_MIGRATION_JDBC_URL`: `jdbc:mysql://<host>:<port>/pawbridge_payment`
- `PAYMENT_MIGRATION_USERNAME`: 이 스키마 전용 계정
- `PAYMENT_MIGRATION_PASSWORD`: 계정 비밀번호
- `PAYMENT_MIGRATION_CONFIRM_TARGET`: 변경 승인 후에만 JDBC URL과 같은 값

`schemaInspect`는 테이블 DDL만 읽는다. `schemaInfo`는 이력을 조회한다. `schemaValidate`는 이력과 SQL 파일을 비교하며 실제 DDL 일치 검사가 아니다. `schemaMigrate`는 명시적 대상 확인 값이 있어야 실행한다.

자동 baseline, clean, repair, undo와 다른 스키마 접속은 제공하지 않는다. URL에는 단일 호스트와 명시적 포트만 허용한다. TLS 추가 옵션이 필요한 환경은 먼저 설정 계약을 확장한다. 원문 예외 대신 종류만 출력한다.

## 격리 MySQL 검증

운영 DB나 운영 포트포워딩을 연결하지 않는다. 전용 컨테이너에 빈 `pawbridge_payment`를 만든다. 테스트 root 비밀번호는 `local_flyway_test_only`다. 실제 계정에 사용하지 않는다.

```sql
CREATE DATABASE IF NOT EXISTS flyway_test_guard;
CREATE TABLE flyway_test_guard.payment_guard (marker VARCHAR(64) NOT NULL);
INSERT INTO flyway_test_guard.payment_guard VALUES ('payment-flyway-disposable');
```

`PAYMENT_MIGRATION_TEST_PORT`에 전용 로컬 포트를 지정한다. Java 컨테이너가 전용 MySQL 컨테이너의 네트워크를 공유하면 3306이다.

```bash
bash ./gradlew --no-daemon migrationMysqlTest
```

보호 마커 확인 후 이 서비스의 고정 테이블 목록과 테스트 테이블·이력만 초기화한다. 다른 MS 테이블은 초기화하지 않는다. V1 생성, JPA 엔티티 2개 호환성, SQL 누락과 기존 스키마 거절을 검증한다. 기존 행 보존 baseline 시험은 `migrationRehearsal`로 따로 실행한다.

## 검증 기록 — 2026-09-11

Java 17과 격리 MySQL 8.4.12에서 단위 테스트 15개, MySQL 통합 테스트 6개가 실패·건너뛰기 없이 통과했다.
API `bootJar`와 `migrationDistribution` 생성도 통과했다. API JAR에는 Flyway 도구·라이브러리가 없고, migration JAR에는 해당 서비스 V1만 있으며 테스트 fixture는 없음을 확인했다.
전체 애플리케이션 회귀 테스트와 운영 baseline·권한 변경·배포는 수행하지 않았다.

## 운영 전환 조건

1. 최신 운영 DDL과 V1의 차이를 확인하고 복구 가능한 백업을 검증한다.
2. 기존 DB의 V1 baseline 대상과 명령을 별도 승인받는다. 이 도구는 baseline 명령을 노출하지 않는다.
3. 전용 마이그레이션 계정과 배포 전 Job을 연결한다. 실패하면 새 애플리케이션 배포를 막는다.
4. 서비스 전환 시 Hibernate를 `validate`로 통일한다. 기존 `update` 실행과 마이그레이션이 경쟁하지 않도록 순서를 정한다.
5. 운영 변경 승인 후에만 migrate를 실행한다. MySQL DDL 실패 시 자동 롤백을 가정하지 않는다.
