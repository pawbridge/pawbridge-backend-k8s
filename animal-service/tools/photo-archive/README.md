# APMS 사진 보관 실행 계약

이 기능은 `animal-service`의 기존 APMS 수집·ES 반영 작업과 별도로 동작한다.
기본 비활성화이며 API 부팅 시 스키마를 자동 변경하지 않는다. 사이트의
`imageUrl`/`imageUrl2`는 APMS 출처 URL로 유지한다. 여기서 생성하는 것은
사진 보관 레코드와 내용 해시 기반 객체이며, 공개 사진 주소 전환은 포함하지 않는다.

## 준비 및 활성화

1. 기존 독립 schema-migration 실행 절차로 V4를 검토·적용한다. 운영 적용에는
   해당 환경의 별도 승인이 필요하다. [스키마 실행 문서](../schema-migration/README.md)를 따른다.
2. Python `Dockerfile.photo`의 내부 압축 서비스를 준비한다. 기존 AI 이미지 CI는
   이 별도 이미지의 발행을 대신하지 않는다. GPU 모델을 로드할 필요는 없다.
3. 보관용 R2 버킷과 `apms/photos/` 전용 prefix에 대해 읽기/쓰기 권한을 확인한다.
   아래 bucket은 기존 공개 업로드 버킷으로 자동 대체되지 않는다. S3 연결과 자격
   증명은 기존 `spring.cloud.aws` 설정을 사용하므로 해당 자격의 버킷 권한이 필요하다.
4. 승인된 소량으로 실행 결과와 부하를 확인한 뒤 스캔/처리량을 조정한다.
   모든 과거 사진을 한 번에 받지 않는다. 원격에서 이미 없어진 파일은 복구할 수 없다.

| 환경 변수 | 의미 / 기본값 |
| --- | --- |
| APMS_PHOTO_ARCHIVE_ENABLED | `false`; 활성화 시에만 스케줄러/DB/외부 요청 생성 |
| APMS_PHOTO_ARCHIVE_BUCKET | 승인된 전용 보관 버킷, 기본값 없음 |
| APMS_PHOTO_OPTIMIZER_URL | `http://<내부 호스트>:<포트>/internal/photos/optimize` 전체 URL |
| APMS_PHOTO_OPTIMIZER_INTERNAL_API_KEY | 압축 서비스와 일치하는 런타임 Secret |
| APMS_PHOTO_ARCHIVE_SCAN_SIZE | 신규와 과거 탐색 각각 최대 50 동물 |
| APMS_PHOTO_ARCHIVE_MAX_PHOTOS | 실행당 처리 시도 최대 25 사진 |
| APMS_PHOTO_ARCHIVE_MAX_RUN_SECONDS | 300초; 시간이 지나면 다음 사진을 시작하지 않음 |
| APMS_PHOTO_ARCHIVE_LEASE_SECONDS | 300초; 최소 300, DB 시각으로 만료 판정 |
| APMS_PHOTO_ARCHIVE_RECHECK_SECONDS | 604800초(7일); 완료 사진의 재확인 간격 |
| APMS_PHOTO_ARCHIVE_INTERVAL_MS | 실행 종료 후 900000ms(15분) |
| APMS_PHOTO_ARCHIVE_INITIAL_DELAY_MS | 기동 후 60000ms(1분) |

전체 실행 제한은 진행 중인 사진을 강제로 자르는 시간이 아니다. 한 사진은
APMS HTTP 30초, 압축 HTTP 60초, S3 요청별 30초(최대 GET/PUT/GET 3회)로
제한한다. 인코딩 실패·입력 초과를 원본 저장 성공으로 대체하지 않는다.

## 처리·복구 규칙

- `apms_photo_scan`은 신규 ID 커서와 과거 ID 순회 커서를 DB 트랜잭션으로 저장한다.
  `updated_at`만 조회하지 않으므로 APMS의 직접 UPDATE와 오래된 사진도 탐색한다.
- 두 슬롯의 작업은 `(animal_id, slot)`으로 구분한다. 공고번호는 식별 키가 아니다.
  실행 중 최신 동물 우선과 오래 대기한 사진 우선을 번갈아 선택한다.
- source URL 변경은 generation을 증가시키며 이전 임대를 무효화한다. 현재 동물
  URL과도 비교하므로 다음 발견 전 사진이 바뀌었을 때도 이전 완료를 거절한다.
- 동일 URL의 사진도 주기적으로 내려받고 source 해시를 비교한다. 보관본은
  `apms/photos/<stored SHA-256>.<실제 확장자>`에 저장한다. 동일 bytes는 같은 키를 쓴다.
- R2 객체는 실제 GET bytes/크기/MIME를 검사한다. 기존 객체가 일치하면 PUT을 생략한다.
  PUT 뒤에도 GET으로 검증한 후 DB 성공을 기록한다. ETag만으로 성공 판정하지 않는다.
- 사진 bytes와 HTTP는 DB 트랜잭션 밖에서 처리한다. DB 완료 실패 뒤에는 재시도 또는
  임대 만료로 재처리한다. 오래된 token/generation은 새 작업을 완료시킬 수 없다.
- READY 상태의 다음 확인은 대기 큐와 처리 예산 영향을 받는다. 7일은 재확인 가능
  시각이며 완료 SLA가 아니다. backlog가 크면 스캔/처리량을 관찰하며 조정해야 한다.
- 일시 오류는 60초부터 최대 1시간 backoff, 404/손상/과대 입력/무결성 오류는 24시간
  재확인으로 남긴다. 새 사진 요청 실패나 출처 제거 때도 마지막 성공 메타데이터와
  R2 객체를 자동 삭제하지 않는다. 저장본이 있는 상태와 최신 사진 확인 성공은 다르다.
- 기본 허용 호스트는 `openapi.animal.go.kr`이다. 자동 리디렉션, userinfo, 임의 호스트,
  비표준 포트는 거절한다. 허용 목록 확대는 실제 APMS 출처를 확인한 후 설정한다.
- 전용 단일 스케줄러를 사용하며 기존 공용 스케줄러를 점유하지 않는다. 여러 API
  인스턴스의 작업은 DB 임대로 구분하지만 전체 동시성은 인스턴스 수에 비례한다.
  압축 API는 자체 1건 제한으로 초과 요청에 503을 반환한다.

운영자가 읽을 수 있는 상태 요약(SQL은 읽기 전용):

```sql
SELECT state, COUNT(*) AS photos, MIN(next_attempt_at) AS oldest_due
FROM apms_photo_archive GROUP BY state;
SELECT error_code, COUNT(*) AS photos
FROM apms_photo_archive WHERE state='RETRY' GROUP BY error_code;
```

중지는 `APMS_PHOTO_ARCHIVE_ENABLED=false`로 설정하고 승인된 배포 절차를 따른다.
DB 테이블·보관 객체를 삭제해 되돌리지 않는다. 실행이 끊긴 작업은 임대 만료 후
회수하므로 중단 직후 PROCESSING 레코드를 수동으로 READY 처리하지 않는다.

## 로컬 검증

Java 17과 서비스 Gradle wrapper를 사용한다.

```sh
bash ./gradlew test --tests 'com.pawbridge.animalservice.photo.*'
bash ./gradlew migrationMysqlTest
bash ./gradlew photoArchiveIntegrationTest
bash ./gradlew bootJar migrationDistribution
```

- `migrationMysqlTest`: `ANIMAL_MIGRATION_TEST_PORT`로 localhost의 폐기 가능한
  MySQL을 지정한다. 스키마 문서의 `flyway_test_guard.guard` 표식이 필수다.
  `pawbridge_photo_archive_test` 스키마를 고정 이름으로 재생성하므로 공유/운영 DB를
  절대 지정하지 않는다. 기존 migration 테스트도 해당 전용 서버에서 실행한다.
- `photoArchiveIntegrationTest`: 같은 MySQL과 `PHOTO_OPTIMIZER_TEST_PORT`의 로컬
  사진 압축 컨테이너가 필요하다. 내부 키는 테스트 전용 `photo-archive-test-only`이다.
  APMS 다운로드는 로컬 생성 사진으로 대체하며, 압축 API와 S3 SDK GET/PUT,
  MySQL 처리는 실제 실행한다. S3 응답은 테스트 JVM의 loopback HTTP 서버다.
- 실제 R2 또는 APMS 대량 조회의 검증이라고 주장하지 않는다. 테스트 컨테이너는
  호스트 포트를 localhost에만 열고 완료 후 해당 테스트 컨테이너만 정리한다.
