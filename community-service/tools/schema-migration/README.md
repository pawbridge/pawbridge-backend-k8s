# Community PostgreSQL 스키마 검증과 적용

Java 17과 Community의 Gradle Wrapper를 사용한다. 아래 명령의 작업 폴더는
`community-service`다. API 기동은 스키마를 변경하지 않으며 Hibernate는
`pawbridge_community`를 `validate`한다.

## 앱 설정을 공급한다

앱은 별도 DB 프로필 없이 PostgreSQL을 사용한다. 다음 값은 실행 환경에서 공급한다.
비밀번호를 명령 인자, 저장소, 로그에 적지 않는다.

- `COMMUNITY_POSTGRESQL_JDBC_URL`: PostgreSQL JDBC URL
- `COMMUNITY_POSTGRESQL_USERNAME`: Community 앱 계정
- `COMMUNITY_POSTGRESQL_PASSWORD`: 앱 계정 비밀번호
- `COMMUNITY_POSTGRESQL_POOL_MAX`: 기본 5
- `COMMUNITY_POSTGRESQL_POOL_MIN`: 기본 0

Hikari는 스키마 `pawbridge_community`와 UTC 세션을 사용한다. Hibernate의
DDL 생성과 SQL 자동 초기화는 사용하지 않는다. 채팅은 DB 프로필과 무관하며
`MEMBER_CHAT_ENABLED` 설정으로 별도 활성화한다.

## 독립 실행 도구를 빌드한다

```bash
bash ./gradlew migrationTest migrationDistribution bootJar
```

API JAR은 마이그레이션 SQL과 Flyway 도구를 포함하지 않는다.
`build/migration/lib`에는 `CommunityPostgresqlMigration`과 필요한 라이브러리가
있다. SQL은 `src/migration/resources/db/postgresql`에 둔다.
이미 적용한 SQL 파일은 수정하지 않고 후속 버전을 추가한다.

## 승인한 로컬 대상의 이력을 조회한다

현재 독립 실행 도구는 루프백의 `pawbridge` DB만 허용한다.
이 도구를 운영 실행기로 오인하거나 원격 URL 제한을 우회하지 않는다.
운영 마이그레이션은 인프라의 승인된 실행 경로와 별도 배포 승인을 따른다.

아래 값은 앱 계정 설정과 구분하여 실행 환경에 공급한다.

- `COMMUNITY_PG_MIGRATION_JDBC_URL`: `jdbc:postgresql://127.0.0.1:<port>/pawbridge`
- `COMMUNITY_PG_MIGRATION_USERNAME`: 승인된 스키마 적용 계정
- `COMMUNITY_PG_MIGRATION_PASSWORD`: 적용 계정 비밀번호

```bash
bash ./gradlew schemaPostgresqlInfo schemaPostgresqlValidate
java -cp 'build/migration/lib/*' com.pawbridge.communityservice.migration.CommunityPostgresqlMigration info
```

`info`는 적용 이력을 조회한다. `validate`는 이력과 SQL 체크섬을 비교하며
실제 데이터 이관이나 모든 테이블의 DDL 일치를 증명하지 않는다.

## 스키마 적용은 별도 승인 후 실행한다

변경 전 백업과 복구 경로를 확인한다. 앱 이미지가 요구하는 SQL 버전과 테이블·
시퀀스 권한을 먼저 적용한 뒤 새 앱을 배포한다. 현재 채팅 앱은 V10까지 필요하다.

DB 관리자가 대상 스키마를 먼저 준비해야 한다. 적용 승인을 받은 뒤에만
`COMMUNITY_PG_MIGRATION_CONFIRM_TARGET`을 위 JDBC URL과 정확히 같은 값으로 공급한다.

```bash
bash ./gradlew schemaPostgresqlMigrate schemaPostgresqlValidate
```

이 도구는 `clean`, `repair`, `undo`, 자동 baseline과 스키마 자동 생성을
제공하지 않는다. SQL 적용 실패 시 새 앱 배포를 중단한다.
새 데이터가 생긴 테이블을 삭제하여 롤백하지 않는다.

## 폐기 가능한 DB에서 회귀를 검증한다

앱 기동·쪽지 HTTP/SSE·로컬 저장소 검증은
[Community 테스트 실행](../../src/test/README.md)을 따른다.
Testcontainers 시험은 직접 생성한 DB만 사용한다.

기존 옵트인 저장 시험은 별도의 폐기 가능한 루프백 `pawbridge` DB가 필요하다.
`migration_test_guard.guard`에 `services-pg-disposable`이 정확히 한 행 있어야 한다.
이 시험은 표식 확인 후 자기 서비스의 스키마 또는 시험 데이터를 초기화한다.
**운영이나 공유 개발 DB에 표식을 추가하여 실행하지 않는다.**

```bash
COMMUNITY_PG_MIGRATION_TEST_PORT=<disposable-local-port> bash ./gradlew migrationPostgresqlTest \
  --tests '*CommunityPostgresqlPersistenceTest'
PRIVATE_NOTES_PG_TEST_PORT=<disposable-local-port> bash ./gradlew migrationPostgresqlTest \
  --tests '*PrivateNotePostgresqlTest'
MEMBER_CHAT_PG_TEST_PORT=<disposable-local-port> bash ./gradlew migrationPostgresqlTest \
  --tests '*MemberChatPostgresqlTest'
```

`migrationPostgresqlHttpTest`에는 별도 표식 `http-rehearsal`, 미리 적용한
SQL과 `PG_HTTP_TEST_PORT`가 필요하다. 공유 DB의 표식을 바꾸어 실행하지 않는다.
영상 시험은 [영상 저장·브라우저 연결 검증](../../src/migrationTest/java/com/pawbridge/communityservice/curation/README.md)을 따른다.

옛 MySQL 실행 도구·SQL·시험·명령은 퇴역했다. 변경 전 자산은 Git 이력으로
보존되며 PostgreSQL 적용 SQL과 운영 DB를 삭제하는 작업이 아니다.
전환 결정과 당시 검증 기록은 Obsidian `Projects/pawbridge`에서 관리한다.
