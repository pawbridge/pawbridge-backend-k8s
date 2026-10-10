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

앱 시험은 PostgreSQL 기본 설정에 테스트 전용 `isolated-test` 설정을 덧씌운다.
별도 `postgresql` 프로필 없이 쪽지 Controller가 등록되고 앱이 기동하는지도 검증한다.
외부에서 전달한 JDBC URL을 쓰지 않고 새 컨테이너의 주소만 등록한다. DB 소유 표식
`community-application-disposable`을 먼저 확인하고 실제 V1~V10 마이그레이션을 적용한 뒤
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

`ci` 설정에도 MySQL 연결이나 Hibernate `create-drop`을 두지 않는다.
공유 이미지 workflow는 Community에만 MySQL 서비스 컨테이너를 시작하지 않는다.
실제 PostgreSQL은 위 앱 시험이 소유한 컨테이너를 사용하며 다른 서비스 CI는 유지한다.
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

## SDK와 로컬 저장소 연동

`S3StorageIntegrationTest`는 Testcontainers로 새 S3Mock을 실행한다. 정식 5.1.0 이미지의
digest를 소스에 고정했으며 Java 17·Spring Boot·AWS SDK·Testcontainers 버전은 바꾸지 않는다.
컨테이너는 512MiB·CPU 1개로 제한하고 HTTP 포트는 loopback에만 임의 배정한다.
호스트 디렉터리나 기존 볼륨을 마운트하지 않으며 시험 종료 후 컨테이너를 제거한다.
처음 실행할 때 Docker 이미지를 다운로드한다. Docker가 없으면 실패하며 자동 skip하지 않는다.

```bash
bash ./gradlew test --tests '*S3StorageIntegrationTest' --tests '*R2ContractGuardTest' \
  --tests '*R2CredentialsConfigTest'
```

시험은 실제 `R2CredentialsConfig`·Spring Cloud AWS 자동 구성·`S3ServiceImpl`을 조립한다.
저장소 SDK나 HTTP transport를 Mock으로 바꾸지 않는다. 앱 전체·DB·회원 서비스·배치는
기동하지 않는다. 시험 설정은 프로세스 환경 변수보다 우선하며 합성 자격 증명만 사용한다.

- 제보 사진의 실제 업로드·읽기·바이트와 MIME/길이 확인·삭제 후 404를 검사한다.
- 게시글 이미지와 영상의 경로·바이트·MIME·삭제를 검사한다. 영상은 합성 바이트로
  전송 계약만 확인하며 디코딩·재생·브라우저 업로드 성공을 의미하지 않는다.
- 첫 제보 사진을 실제 저장한 뒤 두 번째 파일의 허용되지 않는 MIME으로 실패를
  유발한다. 실제 삭제를 통해 첫 객체가 남지 않는지 확인한다. SDK 장애를 재현한 시험은 아니다.
- 동일한 합성 버킷에 둔 운영 경로 모양의 객체·다른 개발 경로·다른 공개 URL의 객체가
  삭제 요청 이후에도 남는지 확인한다. 실제 운영 파일을 시험하지 않는다.

기존 `S3ServiceImplTest`와 `R2CredentialsConfigTest`의 Mock/모의 transport 단위 계약도
유지한다. 로컬 S3Mock은 실제 R2 인증·권한·서명 검증의 증거가 아니다.
S3Mock은 서명 URL을 받아도 서명·만료·HTTP 메서드를 검증하지 않는다.
[S3Mock 정식 버전의 한계](https://github.com/adobe/S3Mock/blob/5.1.0/README.md#important-limitations)를 따른다.

## 실제 R2 계약 시험 — 별도 승인 후에만 실행

`r2ContractTest`는 실행 준비용이며 일반 `test`와 `check`에 포함되지 않는다.
Gradle 실행 단계와 시험 코드가 각각 명시적인 승인을 요구한다. 승인 플래그는 실제
사용자 승인을 대신하지 않는다. 실행 전에 현재 버킷·endpoint·권한과 아래 쓰기 범위를
확인해 별도 승인을 받는다. 기본 테스트 성공을 실제 R2 시험 성공으로 기록하지 않는다.

시험 대상은 기존 `pawbridge-public-images` 버킷이다. 새 버킷이나 버킷 설정을 만들지 않는다.
코드의 승인 endpoint와 `R2_CONTRACT_ENDPOINT`가 정확히 같아야 한다.
매번 새 UUID를 만든 `dev/contract-tests/<UUID>/reports/images/` 아래에 합성 PNG
1개(68바이트)를 저장·읽기·삭제하고 삭제 후 404를 확인한다.
개발 경로는 공개 버킷 안의 논리적 구분이며 그 자체가 IAM 권한 분리는 아니다.
실제 사진·글·DB·계정·Secret·CORS·공개 도메인 설정은 변경하지 않는다.

실행 전 다음 값을 **해당 실행 프로세스 환경에만** 공급한다. `.env` 자동 로딩이나
기본 자격 증명 탐색을 사용하지 않는다. 값은 채팅·명령 이력·PR·로그에 넣지 않는다.

| 환경 변수 | 용도 |
| --- | --- |
| `PAWBRIDGE_R2_CONTRACT_APPROVED` | 별도 승인 후에만 `yes` |
| `R2_CONTRACT_ENDPOINT` | 시험 코드의 승인 endpoint와 같은 값 |
| `R2_CONTRACT_BUCKET` | `pawbridge-public-images` |
| `R2_CONTRACT_ACCESS_KEY_ID` | 승인한 시험용 Access Key ID |
| `R2_CONTRACT_SECRET_ACCESS_KEY` | 승인한 시험용 Secret Access Key |
| `R2_CONTRACT_SESSION_TOKEN` | 임시 자격 증명이 있을 때만 설정, 없으면 미설정 |

```bash
# 별도 승인과 안전한 환경 변수 공급이 끝난 뒤에만 실행한다.
bash ./gradlew r2ContractTest --no-daemon --console=plain
```

시험은 전체 버킷을 조회하거나 비우지 않는다. PUT의 응답을 잃어 객체가 남았을 때에도
정리 조회는 해당 UUID 경로에만 제한한다. 예상 밖의 여러 객체나 다른 키가 있으면
삭제하지 않고 실패한다. 정리도 실패하면 원래 오류와 정리 오류를 함께 보존한다.
이 경우 출력된 개발 경로만 확인해 수동 복구 범위를 승인받으며 다른 경로로 확대하지 않는다.

현재 업로드는 서버가 R2로 전송하는 방식이다. 브라우저 직접 업로드용 서명 URL·CORS나
공개 이미지 CDN 제공·Gateway 인증·프론트 E2E를 이 계약 시험의 통과로 주장하지 않는다.
실제 R2가 서명을 검증하는지는 별도 실행 결과를 확인해야 한다.
[Cloudflare R2 서명 URL 설명](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)도 참고한다.
운영 배포·운영 최소 검증은 이 절차와 별도다.

테스트가 종료되면 이번에 만든 시험 컨테이너만 정리한다. 기존 Compose 서비스·
이미지·볼륨은 보존하며 전체 Docker 정리 명령은 사용하지 않는다.

참고: [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/),
[JUnit 컨테이너 수명](https://java.testcontainers.org/test_framework_integration/junit_5/).
