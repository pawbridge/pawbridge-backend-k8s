# Animal 스키마 도구 실행

## PostgreSQL 격리 스키마 준비

`AnimalPostgresqlMigration`은 신규 PostgreSQL의 `pawbridge_animal` 스키마를
준비하는 별도 실행 경로다. 기존 MySQL runner·SQL·API 시작 설정을 바꾸지 않는다.
현재는 **로컬 격리 DB 검증용**이며 원격 운영 URL은 거절한다.
이 도구의 성공이 API·수집 배치·CDC·검색의 PostgreSQL 전환 완료를 뜻하지 않는다.

- DB 이름: `pawbridge`, 서비스 스키마: `pawbridge_animal`.
- DB 소유자가 `pawbridge_animal` 스키마와 `public`의 `vector`, `pg_trgm` 확장을 먼저 준비한다.
  runner는 스키마 자동 생성·baseline·clean·repair를 허용하지 않는다.
- `db/postgresql/V1`: Animal·보호소·챗봇·Outbox·사진 보관·여행 테이블.
- `V2`: Spring Batch 5.2.4 PostgreSQL 테이블과 실제 sequence 3개.
- `V3`: 재사용 가능한 DINOv3 이미지 특징. 전체 사진 `image_vector`는
  `vector(1024)`, 동물 영역 `animal_vector`는 nullable `vector(1024)`다.
  사진 hash·모델/전처리 버전·추출 상태·색상 JSON/버전도 저장한다.
  기존 DINOv2 384차원은 이관하지 않으며 0으로 패딩하지 않는다.
- `V4`: `pg_trgm` 준비 여부 검사와 공개 목록·구조일 집계용 B-tree 인덱스.
- `V5`: 실종 갤러리의 빌드/스냅샷 문서/현재 공개본 포인터. 완성된 스냅샷만
  트랜잭션으로 공개하는 Python 계약을 지원한다. 스냅샷 문서는 현재 animals의
  상태를 대신하지 않으며, Animal Service의 최종 상태 검증을 유지한다.
- V3 재사용 특징과 V5 공개 스냅샷은 책임이 다르다. 기존 ES 벡터 복사와
  실제 순위 대조, 추천 개선, ANN 인덱스는 별도 검증 범위다.

서버 설치 없이 테스트할 때는 승인받은 임시 pgvector 컨테이너만 사용한다.
검증 기준 이미지: `pgvector/pgvector:0.8.6-pg17`.
운영 버전·이미지 선정은 별도이며 테스트 이미지 tag만으로 운영을 고정하지 않는다.

| 변수 | 값의 형태 |
| --- | --- |
| `ANIMAL_PG_MIGRATION_JDBC_URL` | `jdbc:postgresql://127.0.0.1:<port>/pawbridge` |
| `ANIMAL_PG_MIGRATION_USERNAME` | 격리 DB 계정 |
| `ANIMAL_PG_MIGRATION_PASSWORD` | 격리 DB 비밀번호 |
| `ANIMAL_PG_MIGRATION_CONFIRM_TARGET` | migrate 실행 시 JDBC URL과 정확히 같은 값 |

`localhost`도 허용한다. URL 내 사용자정보·옵션·다중 호스트는 거절한다.
향후 운영 경로에는 TLS와 역할별 권한·연결 제한 검증이 먼저 필요하다.

```bash
bash ./gradlew --no-daemon migrationTest migrationDistribution
bash ./gradlew --no-daemon schemaPostgresqlInfo
bash ./gradlew --no-daemon schemaPostgresqlMigrate
bash ./gradlew --no-daemon schemaPostgresqlValidate
# 또는 패키지에서 독립 실행
java -cp 'build/migration/lib/*' com.pawbridge.animalservice.migration.AnimalPostgresqlMigration info
```

`validate`는 Flyway 이력을 검사한다. 실제 행의 동등성, 인덱스 품질이나
기존 MySQL 쿼리의 PostgreSQL 호환성을 검사하지 않는다.
기존 ID를 명시적으로 가져오면 identity와 Batch sequence를 올바르게 재설정해야 한다.
기존 LocalDateTime 컬럼은 `timestamp(6) without time zone`으로 준비하며
운영 값의 시간대와 문자열 collation·대소문자 비교는 이관 리허설에서 별도로 검증한다.

### 실제 PostgreSQL 통합 테스트

컨테이너는 loopback에만 포트를 노출하고 메모리 상한을 설정한다.
테스트 전용 비밀번호는 `local_pg_test_only`이며 운영에 사용하지 않는다.
전용 DB `pawbridge` 안에 다음을 준비한다.

```sql
CREATE EXTENSION vector WITH SCHEMA public;
CREATE EXTENSION pg_trgm WITH SCHEMA public;
CREATE SCHEMA migration_test_guard;
CREATE TABLE migration_test_guard.guard (marker TEXT NOT NULL);
INSERT INTO migration_test_guard.guard VALUES ('animal-pg-disposable');
```

`ANIMAL_PG_MIGRATION_TEST_PORT`에 이 임시 컨테이너의 포트를 넣고 실행한다.

```bash
bash ./gradlew --no-daemon --max-workers=1 migrationPostgresqlTest
```

테스트는 위 마커를 확인한 뒤 `pawbridge_animal` 스키마를 삭제·재생성한다.
확장 누락 실패 테스트는 이 **전용 DB**의 vector/pg_trgm 확장을 제거했다가 복구한다.
운영 DB의 포트포워딩을 연결하지 않는다. 입력·마커가 없으면 skip이 아니라 실패한다.
동시 실행하지 않으며, 완료 후 해당 임시 컨테이너와 테스트 데이터만 제거한다.

### 실제 텍스트 표본 비교

`postgresqlSearchComparison`은 외부에서 읽은 공개 동물 표본(최대2,000건/16MiB)을
동일한 폐기 가능 DB 가드로 보호한 스키마에 넣고 실제 검색 구현을 실행한다.
**기존 스키마를 삭제하므로 운영 포트포워딩에 연결하지 않는다.** 원본 표본과 결과는
Git에 넣지 않는다. 한국어 검색의 정답 라벨을 만들거나 ES 결과를 정답으로 간주하는 도구가 아니다.

```bash
bash ./gradlew postgresqlSearchComparison \
  -PsearchFixture=build/pg-search-sample.json -PsearchReport=build/pg-search-comparison.json
```

환경 변수 `ANIMAL_PG_MIGRATION_TEST_PORT`가 필요하다. 보고서는 검색 대상 교집합,
상위20건 교집합, 각 저장소만 반환한 예시와 PostgreSQL count+page 시간을 기록한다.
ES는 운영 VM/전체 인덱스 IDF, PostgreSQL은 작은 격리 표본이므로 시간 차이를
속도 개선율로 보고하지 않는다. 기존 검색과 품질 차이가 있으면 전환을 보류한다.

같은 명령에 `-PkoreanAnalysis`를 추가하면 Nori 분석 → PostgreSQL
`tsvector`/GIN 평가 후보와 V6 실제 갱신·조회 경로를 함께 비교한다. 분석기는 API에도
포함하지만 명시적으로 선택했을 때만 생성한다. 독립 이관 도구에는 포함하지 않는다.
실제 조회 비교는 고정 표본을 최대100행씩 갱신한 뒤 수행하며 운영 데이터에는 쓰지 않는다.
검색어의 분석 토큰을 모두 요구하고 색 접미사와 한정된 활용형을 정규화한다.
단순 명사+서술어 검색은 특징/설명 필드 안의 절 경계와 부정 표지로 만든 관계 근거도
요구한다. 다른 문장·필드의 단어를 연결하지 않는다. 이 규칙은 범용 의미 분석기가
아니며 복합 질의·병렬 대상·이중 부정과 암시적 동의 표현은 별도 평가가 필요하다.
건수 감소나 증가만으로 전체 정확도 향상을 단정하지 않는다.
보고서의 준비 시간은 분석기 초기화 시간을 제외하며, 힙 상한은 메모리 실측값이 아니다.

### 분석 검색 규모·동시 조회·갱신 지연 검증

`postgresqlSearchLoad`는 위와 같은 폐기 가능 DB 마커와 loopback 연결을 요구한다.
**`pawbridge_animal` 스키마를 삭제·재생성하므로 운영 포트포워딩에 연결하지 않는다.**
다른 통합 테스트와 동시에 실행하지 않는다. 직접 운영 데이터를 가져오는 기능은 없다.

```bash
bash ./gradlew postgresqlSearchLoad \
  -PloadFixture=build/pg-load-fixture.ndjson -PloadReport=build/pg-load-report.json
```

- 입력은 최대100,000행/128MiB의 NDJSON이다. 첫 줄은
  `{"_meta":{"rows":61748,"synthetic":true}}` 형태의 출처 메타데이터,
  이후 줄은 ID 오름차순 동물 원문과 보호소 검색 필드다. 합성/실제 표본 여부를
  반드시 표시한다. 사진·벡터·회원 정보는 입력 계약에 포함하지 않는다.
- 100행씩 트랜잭션으로 적재하고 실제 분석 갱신·서비스 조회 경로를 실행한다.
  6개 검색어의 각20회 조회, 4개 동시 읽기, 활성 동물 최대500건의 격리 원문 갱신을
  측정한다. Hikari 최대5연결, 실행 JVM 최대256MiB다.
- 컨테이너 전체 메모리와 `/dev/shm` 상한을 따로 확인한다. 이번 1CPU/512MiB
  격리 구성은 Docker 기본 공유 메모리64MiB에서 동시 검색에 실패했다.
  재검증에는 `--shm-size=128m`를 사용하며 전체 메모리 한도512MiB는 유지한다.
  이 값은 운영 동시 요청/병렬 워커/연결 예산을 검증한 권장 사양이 아니다.
- 준비 단계의 `ANALYZE`는 별도60초 제한이며 검색 요청은 기존5초 제한을 유지한다.
  보고서는 단계별로 저장하고 마지막까지 성공했을 때만 `complete: true`로 표시한다.
- 초기 분석은 100행 단위로 스케줄 지연 없이 수행한다. 정상 동기 저장은 500행을
  1/10/50행씩 묶고, 실제 APMS chunk 크기인 1,000행은 5회 반복해 측정한다.
  이어서 1,000행을 갱신하며, 2개 읽기와 1개 쓰기가 겹치는 동안 결과 누락을 검사한다.
  원문 SQL·분석/검색 문서 저장·전체 트랜잭션 시간을 구분한다. 전체 트랜잭션 시간에는
  연결 획득과 커밋도 포함되므로 정확한 연결 대여 시간이나 HTTP 응답 시간으로 부르지 않는다.
  별도 Hikari 이벤트의 borrowedMs/acquireMicros로 실제 대여 시간·획득 대기를 기록하며,
  단계별 각 최대10,000표본만 보관하고 누락 표본/timeout 수도 함께 확인한다.
- 별도로 직접 SQL 우회 변경의 복구 시나리오를 실행한다. 미준비 키워드 검색은503,
  일반 목록은 계속 조회되고 50행/2초 복구 후 새 단어500건이 모두 검색되어야 한다.
  이는 정상 애플리케이션 동기 저장의 동작이나 지연이 아니다.
- 보고서에는 지연 분포, 서비스에서 실제 생성한 SQL의 EXPLAIN, 저장 크기, 동시 저장/검색,
  직접 SQL 복구의 준비 대기 구간이 담긴다. p95는 작은 반복 표본의 기술 통계이며
  운영 SLO나 수용 용량으로 간주하지 않는다. 합성 데이터는 실제 코퍼스 검증을 대체하지 않는다.
- 전체 원문을 별도로 복사할 때는 자유 서술의 개인정보 가능성, 대상 필드·경로·보존 기간을
  먼저 확인한다. 원문은 Git/문서에 첨부하지 않고 검증 후 임시 입력·컨테이너·전용 볼륨을
  제거한다. 문서에는 민감 원문 없이 측정 수치·환경·해시만 보존한다.

### 기존 추천 자동 생성의 전환 경계

Python의 `/batch/embeddings`뿐 아니라 `/similar`도 벡터가 없으면 DINOv2로
생성·저장한다. 새 저장소 연결 전 두 진입 경로를 제어하고 진행 중인 생성 작업을
종료 확인해야 한다. 차원만 바꾸고 기존 생성기를 새 컬럼에 연결하지 않는다.
이 PR의 스키마 준비는 Python 작업을 중지하거나 운영 벡터를 삭제하지 않는다.

### Animal 관계형 PostgreSQL 호환 경로

`application-postgresql.yml`은 명시적으로 선택하는 API/배치 연결 프로필이다.
기본 프로필과 운영 배포 설정은 변경하지 않는다. PostgreSQL JDBC 드라이버는
API에도 포함하지만 Flyway runner와 DDL은 계속 별도 패키지로 분리한다.

- `ANIMAL_POSTGRESQL_JDBC_URL`, `ANIMAL_POSTGRESQL_USERNAME`,
  `ANIMAL_POSTGRESQL_PASSWORD`를 별도로 주입한다.
- 기존 HikariCP를 사용하며 스키마는 `pawbridge_animal`, 세션 시간대는 UTC다.
  Hibernate는 `validate`, Batch/SQL 자동 DDL은 비활성화한다.
- 최대/최소 연결은 `ANIMAL_POSTGRESQL_MAX_POOL_SIZE`와
  `ANIMAL_POSTGRESQL_MIN_IDLE`로 설정한다. 기본 10/2는 운영 전체 연결 예산을
  검증한 값이 아니다. 서비스별 replica·배포 surge·수집 세션·CDC를 합산해야 한다.
- `spring.batch.job.enabled=false`는 Boot 시작 시 Job 자동 실행만 제어한다.
  사진·여행·보호소 스케줄과 외부 CronJob/수동 API 호출은 별도로 제어해야 한다.
  프로필을 선택한 것만으로 수집이 전부 중지되었다고 판단하지 않는다.
- APMS와 보호소는 동일한 PostgreSQL 세션 advisory lock을 공유한다.
  여행은 별도 키를 사용한다. 소유 확인 시 잠금을 재획득하지 않는다.
  수집 잠금 연결은 전체 실행 동안 필요하므로 조기에 풀로 반납하지 않는다.
- 사진 다운로드·R2 저장은 기존 짧은 claim/complete 트랜잭션 밖에서 수행한다.
  보호소 저장은 수집기가 빌린 연결을 그대로 사용한다.
- 사진·보호소·여행의 upsert/JSON/시간/행 잠금을 DB에 맞게 선택한다.
  보호소 JSON patch는 현재 공급자의 평면 scalar 필드 계약에 한정한다.
- 갤러리 스트리밍은 기존 읽기 트랜잭션을 유지하며 PostgreSQL fetch size는
  200을 사용한다. MySQL의 스트리밍 sentinel은 기존 값을 유지한다.

실제 JDBC 저장·조회, Hikari 반환, Batch 메타데이터, Hibernate 매핑 검증은
`migrationPostgresqlTest`에 포함된다. API 전체 기동, 기존 데이터의 시간대와
정렬/대소문자 의미, CDC, ES 검색 교체, Python 벡터 풀은 이 테스트의 범위 밖이다.
따라서 이 프로필만 운영에 적용하는 것을 이관 절차로 사용하지 않는다.

### PostgreSQL 목록·텍스트 검색의 별도 선택

DB 연결 프로필과 검색 backend 선택은 별개다. `AnimalQueryService`의 기본 구현은
기존 Elasticsearch이며, 아래 설정을 명시해야 PostgreSQL 검색 후보 구현을 선택한다.

```yaml
pawbridge:
  animal-query:
    backend: postgresql
```

PostgreSQL 연결 프로필과 V1~V4가 먼저 필요하다. 기본 상태는 NOTICE/PROTECT,
공고번호는 대소문자를 포함한 완전 일치이며 명시한 번호를 조회할 때는 기본 상태
제한을 적용하지 않는다. 명시적 상태·축종·성별·중성화·보호소·지역·나이 필터,
전체 건수, 안정적인 ID 보조 정렬과 기존 페이지 한도를 유지한다.

한국어 후보 검색은 필드별 문자 일치·단어 커버리지·낮은 점수의 `pg_trgm`
오타 일치를 결합한다. 품종 완전 일치를 우선한다. ES Nori 형태소 분석/BM25와
동일하지 않으며 조사·동의어·띄어쓰기·실데이터 기대 순위 비교가 남아 있다.
입력은 200자/8단어, 페이지는 최대 100개/offset 범위 10,000건으로 제한하고
쿼리와 읽기 트랜잭션에 5초 제한을 둔다. 페이지/count는 같은 읽기 snapshot을 사용한다.
현재 B-tree는 구조화 필터용이며, 텍스트 함수 전체가 인덱스로 가속된다고 주장하지 않는다.

`AnimalPostgresqlQueryTest`는 실제 SQL, JPA 통계, Controller→Facade→검색 경로,
20,006건 합성 표본의 검색 시간을 검증한다. MockMvc 연결 검증에는 운영 인증,
외부 인프라와 애플리케이션 전체 기동이 포함되지 않는다. 관련 기록은 Obsidian의
`Animal PostgreSQL 목록 검색과 통계 검증 2026-09-19`를 따른다.

실데이터 한국어 검색의 품질·부하 비교를 마치기 전 운영 backend를 전환하지 않는다.
이 설정은 Python 실종/유사 벡터 검색·ES 인덱싱/CDC를 바꾸지 않으므로 ES 제거
명령이나 전체 이관 완료 조건으로 사용해서는 안 된다.

### 분석 검색 갱신 생명주기 (V6, 명시적 선택)

`backend: postgresql`만 선택하면 위 문자/pg_trgm 경로를 유지한다. 분석 검색은
V6까지 적용한 격리 환경에서 아래처럼 별도로 선택한다. 이 예시는 운영 적용 승인이 아니다.

```yaml
pawbridge:
  animal-query:
    backend: postgresql
    analyzed-text: true
    projection-schedule-enabled: false # 별도 검증 후 켜는 지속 갱신 스케줄
    projection-page-size: 50          # 1~100
    projection-delay-ms: 2000
```

- 원문 텍스트 변경은 DB 트리거가 같은 트랜잭션에서 revision 증가와 dirty 표시를 한다.
  따라서 JPA dirty checking과 APMS bulk UPDATE를 모두 포착한다. 입력 값이 같거나
  상태·찜 수만 변경되면 재분석하지 않는다. 신규/기존 초기 행은 dirty 상태로 시작한다.
- `PostgresqlSearchProjector`는 최대100개 원본 행을 `FOR UPDATE SKIP LOCKED`로 잡고
  분석 결과 저장과 dirty 해제를 같은 짧은 트랜잭션으로 커밋한다. 실패는 페이지 전체를
  롤백하고 다음 실행에서 재시도한다. 기존 업무 트랜잭션 안에서 호출하지 않는다.
  동물/보호소는 별도 트랜잭션이며 SQL4초·잠금1초·트랜잭션5초 상한을 둔다.
- 스케줄은 기본 꺼져 있으며 켜면 반복당 보호소1페이지+동물1페이지만 처리한다.
  새 스레드풀이나 전체 동물 목록 메모리 적재를 만들지 않는다. 여러 파드가 켜져도
  잠긴 행은 건너뛴다. 스케줄을 켜려면 PostgreSQL/분석 검색도 함께 선택해야 한다.
- 보호소 이름/주소는 별도 문서 한 건만 갱신한다. 동물 문서에 대량 복제하지 않는다.
  원본 삭제 시 FK CASCADE로 검색 문서를 지운다. 삭제된 문서를 워커가 재생성하지 않는다.
- 정상 등록/설명 변경/APMS 쓰기/보호소 수집은 `SearchDocumentWriter`가 원문과 검색 문서를
  같은 트랜잭션에 저장한다. 분석 또는 검색 문서 저장 실패는 원문도 롤백한다.
  Projector와 스케줄은 초기 적재·복구용이며 정상 저장 후 검색 대기를 만드는 필수 경로가 아니다.
- 키워드 검색은 원본·검색 문서의 존재/revision/분석 버전을 같은 읽기 snapshot에서 확인한다.
  준비되지 않은 대상이 있으면 HTTP503을 반환한다. 요청 중 원문 재분석·임시 문서 보완은 없다.
  준비된 자료는 동물·보호소 인덱스로 후보를 좁힌 뒤 전체 단어·관계·점수를 최종 검증한다.
  단어가 동물/보호소에 나뉘어 있어도 일치해야 하며, 중복 후보는 결과 개수에 중복 반영하지 않는다.
- 품종만 검색하는 경우는 문자 부분 일치이며 분석 문서 준비를 요구하지 않는다.
  초기 대량 적재/분석 버전 전환은 트래픽 투입 전에 완료한다. 다른 지역의 미준비 자료는
  현재 구조화 조건에 포함되지 않으면 해당 요청을 막지 않는다.
- 단어 분석 버전은 `KoreanSearchAnalyzer.VERSION`이다. Projector는 dirty뿐 아니라
  문서 누락·revision/분석 버전 불일치도 복구한다. SKIP LOCKED의 0건만으로 완료를 판단하지 않는다.
  구버전 writer와 신버전 재생성기가 섞여 문서를 반복 덮어쓰지 않도록 전환 순서를 정해야 한다.
- GIN을 강제하지 않는다. 조건에 따라 PostgreSQL이 순차 탐색을 선택할 수 있으며
  실제 실행 계획·대량 비용을 측정해 판단한다. 초기 적재/준비 확인 뒤 트래픽을 전환한다.

참고: [pgvector 저장 타입](https://github.com/pgvector/pgvector),
[Flyway PostgreSQL 지원 모듈](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).

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
