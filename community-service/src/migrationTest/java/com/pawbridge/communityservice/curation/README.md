# 영상 저장·브라우저 연결 검증

Java 17과 서비스 Gradle Wrapper를 사용한다. 일반 `migrationTest`는 PostgreSQL
태그를 제외한다. 실제 DB 계약은 아래 전용 태스크에서만 실행한다.

```bash
HOME_VIDEOS_PG_TEST_PORT=<disposable-local-port> bash ./gradlew migrationPostgresqlTest \
  --tests 'com.pawbridge.communityservice.curation.HomeVideoPostgresqlTest'
HOME_VIDEOS_PG_TEST_PORT=<disposable-local-port> bash ./gradlew videoBrowserServer
```

DB는 localhost의 폐기 가능한 `pawbridge` 데이터베이스, 합성 테스트 계정이다.
`migration_test_guard.guard`의 표식 `services-pg-disposable`이 정확히 하나여야 한다.
테스트는 해당 DB의 영상 목록을 초기화하며 운영 DB에는 실행하면 안 된다.
브라우저 서버는 기존 영상이 있으면 중단하고 스스로 데이터를 지우지 않는다.

저장 테스트는 V1~V9 Flyway migrate/validate, JDBC 저장과 실제 트랜잭션·행 잠금,
리비전 충돌, 슬롯 제한, 순서, 만료 시 메타데이터 제거, 공급자 실패와 관리자
재확인/스케줄 경쟁을 검사한다. 브라우저 서버는 실제 영상 Controller·Service·
Repository·예외 처리를 사용하고 외부 YouTube 공급자만 loopback fixture로 대체한다.
Kafka, R2, 인증 서버 및 전체 Community 부팅을 검증하는 환경은 아니다.

프런트 `tests/home-videos/README.md`의 connected 모드와 실제 Gateway를 함께 사용한다.
Gateway의 합성 테스트 JWT 비밀과 실행기의 합성 비밀을 동일하게 지정하고
서버는 localhost에만 바인딩한다. 운영 로그인이나 운영 비밀을 사용하지 않는다.

일반 유닛 검증은 `bash ./gradlew test --tests 'com.pawbridge.communityservice.curation.*'`다.
격리 DB/서버 종료와 테스트 데이터 폐기는 담당 실행자가 작업 소유 범위를
확인한 후 수행한다. 운영 마이그레이션과 키 등록은 이 절차의 범위가 아니다.
