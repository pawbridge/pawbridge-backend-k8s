# Community 테스트 실행

Java 17과 이 서비스의 Gradle Wrapper를 사용한다. 운영 DB·R2 인증정보·회원 계정은
필요하지 않다. 아래 명령은 `community-service` 디렉터리에서 실행한다.

## 앱 기동과 쪽지 회귀

Docker의 Linux 컨테이너 엔진을 먼저 실행한다. `CommunityServiceApplicationTests`는
Testcontainers로 새 PostgreSQL 17 Alpine 컨테이너를 만들고 시험 종료 후 제거한다.
DB 포트는 loopback에만 임의 배정하고 DB 메모리는 384MiB로 제한한다.
Docker가 없으면 이 시험은 실패한다. 자동 skip이나 기존 DB 대체는 하지 않는다.
Testcontainers 버전은 Spring Boot 3.4.11 BOM의 1.20.6을 따른다.
DB 이미지는 시험 소스의 digest로 고정한다. 첫 실행에는 시험 의존성과 이미지 다운로드가
필요하며, 이 컨테이너는 기존 로컬 Compose 환경과 독립적이다.

```bash
bash ./gradlew test --tests '*CommunityServiceApplicationTests' --tests '*PrivateNote*Test'
bash ./gradlew test migrationTest
```

Windows에서는 Java 17을 `JAVA_HOME`으로 설정하고 같은 인자로 `gradlew.bat`를
실행한다. `.bat` 실행 환경에 문제가 있다면 같은 Wrapper를 Java로 직접 실행할 수 있다.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -classpath gradle/wrapper/gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain test migrationTest --no-daemon --console=plain
```

앱 시험은 실제 `postgresql` 프로필에 테스트 전용 `isolated-test` 설정을 덧씌운다.
외부에서 전달한 JDBC URL을 쓰지 않고 새 컨테이너의 주소만 등록한다. DB 소유 표식
`community-application-disposable`을 먼저 확인하고 실제 V1~V9 마이그레이션을 적용한 뒤
Hibernate의 `validate`로 기동한다. 마이그레이션 파일과 Flyway·Testcontainers는
시험 classpath에만 추가되며 운영 JAR에는 포함되지 않는다.
동적 시험 설정은 프로세스 환경 변수보다 우선한다. `SPRING_DATASOURCE_URL`이나
실제 R2 설정이 주변 환경에 있더라도 시험 DB·합성 저장소 설정을 대체하지 못한다.

- 실제 검증: 앱 조립·스키마 검증, HTTP 쪽지 저장·동일 요청 재전송·소유권·읽음 상태,
  커밋 후 실제 SSE 수신, REST/SSE 알림 데이터 일치와 본문 미노출.
  실시간 시각과 DB 저장 시각의 비교는 PostgreSQL의 마이크로초 정밀도를 반영한다.
  잘못된 DB 소유 표식은 마이그레이션·설정 등록 전에 거부하는지도 검사한다.
- Mock 경계: `S3Service`와 `UserServiceClient`. 쪽지 Repository·Service·Stream과
  트랜잭션 관리자는 실제 구현이다. R2·회원 서비스·YouTube에는 요청하지 않는다.
- 백그라운드 작업: 실제 `@EnableScheduling`에 대응하는 테스트 `TaskScheduler`를
  Mock으로 바꿔 업무 배치를 실행하지 않는다. PostgreSQL 선택에서 Elasticsearch
  인덱스 초기화와 소비자가 제외되고 Kafka listener가 없는지도 단언한다.
- SSE 헤더는 서비스 시험이 직접 지정한다. Gateway의 JWT 인증이나 프론트 브라우저를
  포함한 E2E 시험은 아니다. Stream 자체의 heartbeat와 연결 종료는 유지한다.

기존 `ci` 프로필의 MySQL·Elasticsearch 설정과 공유 CI workflow는 변경하지 않는다.
이 앱 시험의 명시적 프로필은 `ci` 환경 변수와 별개로 PostgreSQL 경로를 선택한다.
다른 DB 옵트인 시험이 skip되면 전체 보고서에 skip 수와 이유를 함께 기록한다.

## 기존 PostgreSQL 저장·잠금 시험

아래는 **별도로 준비한 폐기 가능한 로컬 DB**를 사용하는 기존 옵트인 시험이다.
운영이나 공유 개발 DB에 실행하지 않는다. DB 이름은 `pawbridge`, 합성 계정은
`postgres / local_pg_test_only`이며, `migration_test_guard.guard`에
`services-pg-disposable`이 정확히 한 행 있어야 한다. 표식을 임의 DB에 만들어
안전장치를 우회해서는 안 된다. 시험은 이 표식 확인 후 쪽지 시험 데이터를 초기화한다.

```bash
PRIVATE_NOTES_PG_TEST_PORT=<disposable-local-port> bash ./gradlew migrationPostgresqlTest \
  --tests '*PrivateNotePostgresqlTest'
```

`migrationPostgresqlHttpTest`는 다른 표식 `http-rehearsal`과 미리 마이그레이션한 DB를
요구한다. 위 새 자동 앱 시험이나 쪽지 저장 시험과 같은 DB를 그대로 공유하지 않는다.
영상 전용 실행은 [기존 영상 시험 절차](../migrationTest/java/com/pawbridge/communityservice/curation/README.md)를 따른다.

## 검증 범위 밖

S3 Mock 서버와 실제 R2의 업로드·삭제·서명·CORS는 이번 시험에 포함되지 않는다.
기존 `S3ServiceImplTest`와 `R2CredentialsConfigTest`는 SDK Mock/모의 HTTP 경계의
별도 단위 계약을 계속 검사한다. 실제 R2 연동은 개발 전용 객체 경로에서 별도 검증한다.
운영 배포·운영 최소 검증도 이 절차와 별도다.

테스트가 종료되면 이번에 만든 시험 컨테이너만 정리한다. 기존 Compose 서비스·
이미지·볼륨은 보존하며 전체 Docker 정리 명령은 사용하지 않는다.

참고: [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/),
[JUnit 컨테이너 수명](https://java.testcontainers.org/test_framework_integration/junit_5/).
