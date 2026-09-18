# Animal 스키마 도구 실행

## V7 동반여행 이용·시설 정보와 추가 사진

V7은 화면에 노출하는 관광지(12), 문화시설(14), 레포츠(28)에 한해 국문 관광정보 서비스 `KorService2`의 장소별 상세를 저장한다.

- `detailIntro2`: 이용 시간, 휴무일, 주차, 문의처 등 관광 유형별 소개 정보
- `detailInfo2`: 시설·이용 안내의 반복 항목
- `detailImage2`: 대표 사진 외 추가 사진과 공공누리 유형

세 자원은 서로 다른 외부 API 호출이며 성공·실패·재시도 상태도 독립적으로 기록한다. 한 자원의 실패가 다른 자원의 저장을 막지 않는다. 빈 응답도 해당 시점의 정상 스냅샷으로 저장해 매 실행마다 같은 장소를 다시 요청하지 않는다. 장소 원본의 `modified_time`이 바뀌면 기존 상세는 `STALE`이 되고 다시 수집 대상이 된다.

`TOURAPI_MAXVISITDETAILSPERRUN`은 실행 한 번에 **각 자원별로** 처리할 장소 수이며 기본값은 18이다. 따라서 세 자원이 모두 미수집이면 한 실행에서 최대 54회의 장소별 요청을 사용한다. 일일 호출 상한은 기존 `TOURAPI_DAILYREQUESTLIMIT`과 자원별 요청 예산이 함께 제한한다. `detailImage2`는 한 장소의 여러 사진을 한 응답으로 받지만 여러 장소 ID를 한 요청에 묶지는 않는다.

공개 API는 다음 원칙으로 응답한다.

- 소개·반복 정보가 일부만 수집되어도 확보된 항목을 반환하고 상태는 `PARTIAL`로 표시한다.
- 추가 사진은 공공누리 1유형과 3유형만 저장·노출한다. 2유형은 상업 기능과의 결합 가능성을 고려해 이번 범위에서 제외한다.
- 추가 사진 목록은 장소 상세 조회에서만 읽는다. 목록 조회에서 장소별 사진 쿼리를 반복하지 않는다.
- 숨김 장소와 화면 대상이 아닌 유형은 새 상세 수집 대상이 아니다.

### V7 적용과 복구

1. 배치 수집을 멈추고 진행 중 실행이 없는지 확인한다.
2. 전용 마이그레이션 계정으로 `schemaMigrate` 후 `schemaValidate`를 실행한다.
3. 새 API·배치 이미지를 배포하고 기존 TourAPI 키가 국문 서비스 호출에 주입됐는지 확인한다.
4. 첫 실행은 기본값 18로 유지하고 `pet_travel_visit_details`, `pet_travel_images`, 요청 예산, 수집 오류를 확인한다.
5. 대표적인 12·14·28 유형 상세에서 이용 정보, 반복 안내, 사진과 각 상태를 확인한 뒤 처리량을 조정한다.

이전 이미지로 되돌릴 때 V7 테이블을 삭제하지 않는다. 이전 코드는 새 테이블을 읽지 않으므로 기존 화면으로 안전하게 복귀하고, 수집을 멈춘 상태에서 원인을 확인한다. MySQL DDL은 자동 롤백을 보장하지 않으므로 마이그레이션 중 실패하면 `repair`로 덮지 말고 실제 생성된 테이블과 Flyway 이력을 먼저 대조한다. V7 데이터는 원본 목록·공통 상세·동반 조건을 대체하지 않으므로 테이블을 보존한 채 전진 수정할 수 있다.

## V6 동반 상세 일괄 수집 전환

이 절차는 `V6__pet_travel_bulk_details.sql`과 `PetTravelCollector`의 전환 설정에 대응한다.
코드·로컬 검증과 운영 반영은 별개다. 아래 명령은 운영 실행 승인을 받은 뒤 적용한다.

1. 수집 실행을 멈추고 실행 중인 수집이 없는지 확인한다. 기존 백업과 복구 경로를 확인한다.
2. 승인된 마이그레이션 계정으로 `schemaMigrate`와 `schemaValidate`를 실행한다. V6 완료 전에는 새 API·배치 이미지를 시작하지 않는다.
3. 국문 관광정보 서비스 키를 `TOURAPI_KOREANSERVICEKEY`로 주입한다. 기존 운영의 `TOURAPI_BULKSERVICEKEY`도 호환된다. `TOURAPI_SERVICEKEY`는 반려동물 동반여행 서비스용으로 유지한다.
4. 새 API·배치 이미지에 같은 설정을 적용한다. `TOURAPI_BULKPETENABLED=true`로 전환하고 수집을 한 번 실행한다.
5. `pet_travel_pet_collection_state`의 `next_page`, `completed_at`, `error_code`와 `pet_travel_request_budgets`의 `PET_BULK` 사용량을 확인한다.
6. 기본 정보가 공개된 장소에서 동반 조건을 조회한다. 공통 소개글이 없어도 확보된 조건이 표시되어야 한다. 숨김 장소와 기본 목록에 없는 ID는 공개되지 않아야 한다.

화면용 공통·동반 상세는 국문 `KorService2`의 `detailCommon2`, `detailPetTour2`에서 수집한다. 일괄 동반 상세는 ID 없이 페이지당 100건씩 요청한다.
`TOURAPI_MAXBULKPAGESPERRUN`의 기본값은 10이다. 저장한 페이지 다음부터 재개하며, 한 순회를 마치면 24시간 동안 새 순회를 시작하지 않는다.
목록·표출 여부는 기존 `KorPetTourService2`에서 계속 수집한다. 공통 상세는 국문 서비스에 장소별로 요청한다.
API의 실제 이용 한도와 별개로 `dailyRequestLimit`은 작업별 일일 호출 상한을 제한한다. 실패한 HTTP 시도도 사용량에 포함한다.

V6는 기존 공개 동반 상세를 독립 테이블로 복사한다. 빈 동반 조건도 해당 ID의 응답을 실제 받은 경우에만 수집 성공으로 처리한다.
일괄 목록에 없다는 이유로 기존 상세를 삭제하거나 동반 불가로 바꾸지 않는다. 기본 14일이 지난 일괄 상세는 `STALE`로 반환한다.
장소 수정이 감지되면 이전 일괄 상세도 `STALE`로 반환한다. 숨김 후 재표출은 새 동반 상세를 받기 전까지 이전 조건을 공개하지 않는다.

### 실패 시 재개와 전환 취소

- HTTP 실패·일일 한도 소진: 저장된 페이지를 유지한다. 다음 실행에서 같은 페이지를 시도하며, 개별 동반 API로 자동 우회하지 않는다. 목록·공통 상세 수집은 계속한다.
- 총 건수 변경·이전 페이지와 중복 ID: 오류를 기록하고 일괄 커서를 1페이지로 돌린다. 이미 확보한 상세는 유지한다.
- 프로세스 강제 종료: 행과 커서는 같은 트랜잭션으로 저장된다. 기존 `TRAVEL_ORPHAN_RUN` 보호는 유지되므로, 실제 프로세스 종료와 잠금 소유자를 확인한 뒤 기존 중단 실행 복구 절차를 적용한다. 실행 중인 작업을 정상 종료로 덮지 않는다.
- 일괄 방식만 중단: 수집을 멈춘 뒤 `TOURAPI_BULKPETENABLED=false`로 전환한다. 공통·개별 동반 상세는 국문 서비스를 계속 사용한다. 이미 처리된 모든 장소가 즉시 재수집되는 것은 아니다.
- 이전 이미지로 복구: V6 테이블과 열을 삭제하지 않는다. 기존 목록·공통 상세 테이블은 보존되지만, 이전 이미지는 새 독립 상세를 읽지 못한다. 일괄 방식으로만 확보한 조건은 이전 이미지에서 표시되지 않을 수 있다.

### 일괄 상세 화면 회귀 검사

이 검사는 실제 수집기·MySQL·Controller와 기존 프런트를 연결한다. 관광공사 응답만 합성 데이터다.
운영 API, Gateway, TLS, CORS는 검증하지 않는다. 테스트용 프로세스는 localhost에서만 실행한다.

1. 아래 격리 MySQL 절차의 DB에서 `migrationMysqlTest --tests '*initial_schema_matches_all_entities_and_initializes_batch_sequences'`를 실행한다. 보호 표식 확인 후 빈 카탈로그가 준비된다.
2. `testClasses bootJar`를 실행한다.
3. 환경변수 `PAWBRIDGE_TRAVEL_CONTRACT_TEST=true`, `PAWBRIDGE_TRAVEL_BULK_CONTRACT_TEST=true`, `ANIMAL_MIGRATION_TEST_PORT=<격리 DB 포트>`를 주입한다. 다음 서버를 실행한다.

```bash
java -Xmx256m -Dloader.path=build/classes/java/test \
  -Dloader.main=com.pawbridge.animalservice.travel.PetTravelContractServer \
  -cp build/libs/animal-service-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

Windows Java를 WSL에서 실행한다면 `loader.path`와 JAR 경로는 Windows 절대 경로로 바꾼다.
위 세 환경변수는 기존 항목을 보존하면서 `WSLENV`에 추가한다.

4. `TRAVEL_DB_CONTRACT_READY requests=5`를 확인한다. 그 뒤 WSL에서 `PAWBRIDGE_TRAVEL_CONTRACT_TEST=true node src/test/browser/pet-travel-bulk-relay.mjs`를 실행한다.
5. 기존 프런트 저장소에서 `npm run build` 후 `npm run preview -- --host 127.0.0.1 --port 5198 --strictPort`로 연다.
6. 설치된 Chromium 경로를 지정한 설정으로 전용 Playwright CLI 세션을 연다. 다음 검사를 실행한다.

```bash
playwright-cli -s=pawbridge-bulk-e2e open about:blank --config=<로컬 브라우저 설정 경로>
playwright-cli -s=pawbridge-bulk-e2e run-code --filename=src/test/browser/pet-travel-bulk.browser.js --raw
playwright-cli -s=pawbridge-bulk-e2e close
```

13개 검사는 공통 소개글 없이 조건 공개, 미등록 ID 404, 데스크톱·모바일 표시, 가로 넘침, 지역 복귀 링크, 조회 중 공급자 미호출을 확인한다.
완료 후 이 검사에서 시작한 서버·릴레이·미리보기·DB 컨테이너만 종료한다.

## V1 도입 시 검증 실행 기준 (2026-09-11)

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

## V1 도입 당시 실행 범위

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
