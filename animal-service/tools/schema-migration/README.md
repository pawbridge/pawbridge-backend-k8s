# Animal 스키마 도구 실행

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

이 실행 절차는 `gradle/schema-migration.gradle`과 별도 migration 소스셋에 대응한다.
모든 명령은 Java 17 환경에서 `animal-service` 디렉터리를 기준으로 실행한다.

## 현재 실행 범위

현재 구현은 **운영 DDL에 근거한 V1과 독립 실행 도구**다. 운영 DB baseline, Kubernetes Job과 Vault 권한 연결은 포함하지 않는다.
`src/migration/resources/db/migration/V1__animal_schema.sql`은 2026-09-11 운영 MySQL 8.4.12의 테이블 17개를 `SHOW CREATE TABLE`로 확인해 작성했다.
JPA 테이블 8개와 Spring Batch 테이블 9개를 포함한다. 당시 뷰·트리거·루틴·이벤트는 없었다.
기존 `utf8mb3`, 인덱스, 외래키, enum 정의를 보존한다. 문자셋 변경은 별도 마이그레이션에서 다룬다.
운영 행과 현재 `AUTO_INCREMENT` 값은 제외하고, 새 DB에서만 사용할 Batch 시퀀스 초기값 0을 넣었다.
`migrate`와 `validate`는 마이그레이션과 이력이 모두 없으면 실패한다. `info` 출력은 운영 도입 완료를 뜻하지 않는다.

Flyway는 API 애플리케이션의 의존성에 포함되지 않는다. API 서버 시작이나 `bootJar` 생성은 이 도구를 실행하지 않는다.
기존 Hibernate 및 Spring Batch 초기화 설정도 이 변경에서 바꾸지 않는다.

## 로컬 빌드와 단위 검증

```bash
bash ./gradlew --no-daemon migrationTest migrationDistribution
```

실행 파일과 라이브러리는 `build/migration/lib`에 생성된다. API Dockerfile의 `build/libs/*.jar`와 분리된다.
테스트용 V1 fixture는 제거했다. 테스트는 실제 `db/migration`의 SQL을 사용하며, 비어 있지 않은 DB 거절 시험에서만 시험 테이블을 직접 만든다.
패키지 자체를 실행할 때는 의존성을 포함한 classpath를 사용한다.

```bash
java -cp 'build/migration/lib/*' com.pawbridge.animalservice.migration.AnimalSchemaMigration info
```

## 접속 정보 주입

아래 환경변수는 승인된 실행 환경에서 주입한다. 운영 비밀번호를 명령행 인자, Git, 문서에 적지 않는다.
운영 연결은 향후 Vault에서 별도 마이그레이션 계정으로 주입한다. 애플리케이션 계정에 DDL 권한을 추가하지 않는다.

| 변수 | 값의 형태 |
| --- | --- |
| `ANIMAL_MIGRATION_JDBC_URL` | `jdbc:mysql://<host>:<port>/pawbridge_animal` |
| `ANIMAL_MIGRATION_USERNAME` | 대상 스키마 전용 계정 |
| `ANIMAL_MIGRATION_PASSWORD` | 해당 계정 비밀번호 |
| `ANIMAL_MIGRATION_CONFIRM_TARGET` | 변경 실행 승인 후 JDBC URL과 정확히 같은 값 |

현재 URL 형식은 단일 호스트와 명시적 포트만 지원한다. URL 내 인증정보, 쿼리 옵션, 다중 호스트, 다른 서비스 스키마는 거절한다.
TLS 인증서 옵션이나 IPv6 연결이 필요한 환경은 지원 범위를 먼저 확장·검증한다. 이 도구가 TLS 보안 정책을 검증한다는 뜻은 아니다.

## 기존 테이블 구조 확인

읽기 전용 계정을 사용한다. `SHOW CREATE TABLE` 결과에는 테이블 설명 등 내부 메타데이터가 포함될 수 있으므로 공개 게시하지 않는다.

```bash
bash ./gradlew --no-daemon schemaInspect
```

이 명령은 테이블 DDL만 출력하며 행 데이터와 계정 비밀번호는 출력하지 않는다.
뷰, 트리거, 루틴, 이벤트, 계정 권한은 수집하지 않는다. 별도 확인 전에는 전체 DB 백업이나 완전한 기준 SQL로 취급하지 않는다.
결과의 현재 `AUTO_INCREMENT` 값도 초기 생성용 SQL로 확정하기 전에 검토한다.

## 이력 확인과 검증

```bash
bash ./gradlew --no-daemon schemaInfo
bash ./gradlew --no-daemon schemaValidate
```

`validate`는 Flyway 이력과 마이그레이션 파일을 비교한다. 운영 테이블이 JPA 엔티티와 일치하는지 검사하는 명령이 아니다.
오류에는 원문 SQL이나 연결정보가 포함될 수 있어 실행 도구는 예외 종류만 출력한다.
실패하면 입력 변수, 대상 계정 권한, 스키마 존재 여부, 기준 SQL 승인 상태, `info` 결과를 확인한다.

## 격리 MySQL 검증

운영 DB나 운영 DB 포트포워딩을 테스트에 연결하지 않는다.
테스트는 로컬의 전용 MySQL 8.4 컨테이너에서만 실행한다.
컨테이너에는 `pawbridge_animal`과 다음 보호용 스키마를 생성한다.

```sql
CREATE DATABASE flyway_test_guard;
CREATE TABLE flyway_test_guard.guard (marker VARCHAR(64) NOT NULL);
INSERT INTO flyway_test_guard.guard VALUES ('animal-flyway-disposable');
```

테스트 전용 root 비밀번호는 `local_flyway_test_only`다. 실제 계정의 비밀번호로 사용하지 않는다.
호스트 Java에서 실행하면 컨테이너 포트를 `127.0.0.1`에만 공개하고 할당된 포트를 `ANIMAL_MIGRATION_TEST_PORT`에 설정한다.
Java도 Docker에서 실행하면 `--network container:<전용-MySQL-컨테이너명>`으로 네트워크를 공유하고 테스트 포트를 `3306`으로 설정한다.
Docker의 host 네트워크와 Windows 호스트 포트가 같은 localhost를 가리킨다고 가정하지 않는다.

```bash
bash ./gradlew --no-daemon migrationMysqlTest
```

테스트는 보호용 마커를 확인한 다음 **이 격리 DB의** `migration_probe`, `flyway_schema_history`를 초기화한다.
전체 V1 검증을 위해 고정 목록에 있는 animal 테이블 17개도 역의존 순서로 초기화한다.
변수나 보호용 마커가 없으면 테스트는 건너뛰지 않고 실패한다.
완료 후 이 테스트에서 생성한 컨테이너와 볼륨만 제거한다.

## 운영 편입 전 확인

1. 운영 스키마·권한을 읽기 전용으로 확인한다. Spring Batch, 챗봇, 이벤트 처리 테이블도 포함한다.
2. V1과 운영 스키마의 최신 차이를 재확인하고, 빈 MySQL에 전체 animal 스키마가 생성되는지 검증한다.
3. 기존 DB 구조와 기준 SQL을 비교한다. 백업 복구도 확인한다.
4. 기존 DB의 baseline 버전·대상·실행 명령을 별도로 승인받는다. 이 도구는 `baseline`, `clean`, `repair`, `undo`를 제공하지 않는다.
5. 마이그레이션 계정과 배포 전 Job을 연결한다. Job 실패 시 새 애플리케이션 배포를 중단한다.
6. 대상 변경 승인을 받은 후에만 `ANIMAL_MIGRATION_CONFIRM_TARGET`을 주입하고 `schemaMigrate`를 실행한다.

위 조건을 충족하기 전에는 운영 `schemaMigrate`를 실행하지 않는다.
MySQL DDL 실패는 자동 롤백을 보장하지 않는다. 실패 후 `repair`로 덮지 말고 실제 적용 상태를 확인해 복구 또는 전진 수정한다.

## 공식 참고

2026-09-11 로컬 검증: Java 17에서 단위 테스트 15개와 격리 MySQL 통합 테스트 6개를 통과했다.
통합 검증에는 V1 전체 생성, JPA 엔티티 8개 검증, Batch 시퀀스 초기화, 기존 DB baseline 모의 편입과 데이터 보존을 포함했다.
API `bootJar`, 마이그레이션 패키징, 패키지의 독립 `info`·`inspect` 실행도 확인했다.
운영 DB에는 읽기 전용 조회만 실행했다. 운영 baseline·권한 변경·배포 및 전체 animal 회귀 테스트는 이 검증에 포함하지 않았다.

- [Flyway baseline-on-migrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting)
- [Flyway Java API](https://documentation.red-gate.com/flyway/reference/usage/api-java)
- [MySQL DDL과 트랜잭션 처리](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)
