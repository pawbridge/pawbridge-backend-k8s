# APMS 과거 데이터 대조·보정 CLI

`animal-service` JAR의 별도 main으로 실행한다. Spring 컨텍스트, HTTP 서버, 정기 배치,
스키마 자동 생성은 시작하지 않는다. 기본 모드는 읽기 전용 `plan`이다.
APMS 상태/수정일 필터 없이 **접수 월 한 개**를 조회한다. 최대 20페이지/20,000건,
건수·중복 이상 시 동일 월을 한 번 더 조회해 최대 40회로 제한한다.
불일치가 반복돼도 동일 내용의 레코드를 확보했다면 계획에 경고를 남긴다.
이는 공급자 전체 데이터의 완전성을 보증하지 않는다.

## 실행 전 조건

- 운영 실행은 별도 승인 대상이다. 검토한 커밋/JAR SHA-256, DB/ES 환경, 월, 계획 SHA-256,
  최대 변경 건수, 원본 경고, 중단·복구 절차를 함께 승인받는다.
- Java 17, MySQL 8, 기존 `animals` 쓰기 alias와 APMS API 접근이 필요하다.
- 계획/저널은 영속적인 비공개 운영 디렉터리에 저장한다. Linux에서는 `umask 077`,
  디렉터리 권한 700을 사용한다. Windows는 해당 운영자만 접근하도록 디렉터리 ACL을 제한한다.
- 환경 변수는 승인된 Secret 전달 경로를 사용한다. 셸 명령 인자, Git, 로그에 비밀번호·키를 적지 않는다.
- `HISTORY_JDBC_URL`, `HISTORY_DB_USER`, `HISTORY_DB_PASSWORD`, `HISTORY_ES_URL`을 지정한다.
  사설 CA를 쓰는 ES는 `HISTORY_ES_CA_CERT`에 PEM 인증서 경로를 지정한다. TLS/hostname 검증은 끄지 않는다.
  ES 인증이 필요하면 `HISTORY_ES_AUTHORIZATION`에 완성된 Authorization 헤더를 전달한다.
- `plan`은 `APMS_API_BASE_URL`, `APMS_API_SERVICE_KEY`도 필요하다. 서비스키는 **디코딩된 원문 키**다.
  URL 인코딩된 키는 Secret 전달 단계에서 한 번 디코딩한다. 키를 화면에 출력하지 않는다.
- JDBC URL의 자동 재연결은 금지한다. CLI는 물리 DB 연결 하나로 잠금과 쓰기를 수행한다.
- 신규 동물의 보호소는 이미 존재해야 한다. 보호소가 없거나 필수 원본 값이 잘못되면 계획을 중단한다.
  보호소 생성이나 UNKNOWN 대체값으로 오류를 숨기지 않는다.
- alias 교체/전체 재색인 작업과 함께 실행하지 않는다. alias/인덱스 UUID가 바뀌면 중단한다.

## 계획 생성과 검토

```bash
umask 077
java -Dloader.main=com.pawbridge.animalservice.reconciliation.HistoryReconciliationCli \
  -cp "$ANIMAL_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  --month 2026-01 --plan "$RECONCILIATION_DIR/2026-01.plan.json"
```

계획 파일을 덮어쓰지 않는다. 기준 환경은 MySQL server UUID/DB 이름과 ES cluster/index UUID다.
`entries`는 유기번호로 정렬한 신규/변경 후보이며 기존 기록에는 ID와 변경 전 상태·접수일·수정 시각을 담는다.
전체 원본 DTO를 포함하므로 익명 공개 자료로 게시하지 않는다.

검토 시 신규/갱신 수, 보호소 ID, 기존 상태와 원본 상태, 접수일, 원본 수정 시각,
`reported`/`observed`/`warnings`를 확인한다. 최신의 계획을 사용하고 월을 합치거나 수동으로 후보를 추가하지 않는다.
APMS 미반환 기존 DB 기록은 삭제하지 않는다. 수정 시각만 바뀐 레코드는 보정 후보로 만들지 않는다.

## 승인된 계획 적용

다음 명령의 SHA-256/최대 건수/경고 허용 값은 **실제 생성한 계획**으로 채운 후 승인받는다.

```bash
java -Dloader.main=com.pawbridge.animalservice.reconciliation.HistoryReconciliationCli \
  -cp "$ANIMAL_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  --mode apply --month 2026-01 --plan "$RECONCILIATION_DIR/2026-01.plan.json" \
  --sha256 "$REVIEWED_PLAN_SHA256" --max-changes "$REVIEWED_TARGET_COUNT" \
  --journal "$RECONCILIATION_DIR/2026-01.journal.jsonl" \
  --allow-incomplete-source false
```

원본 경고가 있는 월은 해당 경고를 검토·승인한 경우에만 마지막 값을 `true`로 바꾼다.
적용 모드는 APMS를 다시 호출하지 않는다. 검토한 스냅샷의 내용만 사용한다.
DB의 APMS 수정 시각이 더 최신이면 `NEWER_DB_RETAINED`로 DB를 보존하고 현재 DB 값을 검색에 반영한다.
그 외 변경 전 값 불일치(동일 시각의 사용자 상태 변경 등)는 자동 덮어쓰기하지 않고 중단한다.

- 정기 배치와 같은 `pawbridge_animal.apmsAnimalSyncJob` MySQL 잠금을 사용한다.
  잠금 경합/미종료 배치 메타데이터가 있으면 중단한다. 잠금 대기를 자동 반복하지 않는다.
- 동물별 DB 트랜잭션을 커밋한 후 현재 DB 값을 다시 잠금 조회해 검색에 반영한다.
- 신규는 기존 `AnimalItemProcessor.createNewAnimal` 변환 계약을 사용한다. 유기번호가 이미 있으면
  새 행을 만들거나 그 행을 덮어쓰지 않고 현재 행을 검색에 반영한다.
- 기존 행은 `status`, `apms_process_state`, `happen_date`, `apms_updated_at`만 갱신한다.
  ID, 공고번호, 보호소, `updated_at`, 사용자 설명, 찜 수 등 다른 값은 유지한다.
- 기존 검색 문서는 위 네 필드만 optimistic concurrency 조건으로 갱신한다.
  `image_vector`, 설명, 찜 수 등은 요청에 넣지 않는다. 문서가 없으면 현재 DB의 전체 검색 투영을
  `_create`로 생성한다. 이미 다른 작업이 만들었다면 409로 중단한다. 기존 문서를 교체하지 않는다.
- 쓰기 대상은 검토한 alias의 실제 인덱스로 고정한다. 실행 중 alias가 바뀌어도 새 대상에 쓰지 않는다.
- 성공 후 refresh한다. 정기 배치의 `BATCH_*` 실행 이력/성공 기준일을 생성·수정하지 않는다.

## 실패·재개·복구

저널에는 `DB_INTENT` → `DB_COMMITTED` → `SEARCH_SYNCED`가 동물별로 남는다.
예외 시 `INCOMPLETE`, 모든 검색 반영/refresh 완료 후에만 `COMPLETED`를 남긴다.
`COMPLETED`의 `sourceComplete=false`는 보정 적용 완료와 공급자 원본 불완전성을 구별한다.
저널 쓰기 자체가 실패할 수도 있으므로 마지막 이벤트만으로 DB 미반영을 단정하지 않는다.

검색 장애 등 원인을 해결한 뒤 **동일 계획·해시·저널의 같은 명령**으로 재개한다.
저널의 완료 항목을 맹신해 건너뛰지 않고 모든 대상을 다시 대조한다.
이미 저장된 신규 유기번호/이미 적용된 기존 값은 DB를 중복 갱신하지 않으며 현재 DB 상태로 검색을 복구한다.
커밋 응답 전에 연결이 끊긴 경우에도 계획의 유기번호와 변경 전/후 값으로 재판정한다.
따라서 `ALREADY_PRESENT`/`ALREADY_APPLIED`는 이 실행이 그 행을 처음 변경했다는 소유권 증거가 아니다.

중단 기준: DB 비교 충돌, APMS 재조회 내용 변화/상충 중복, 잘못된 필드/보호소,
대상 환경/alias 변경, 잠금 실패, 검색 409/장애, 저널 저장 실패. 자동 무제한 재시도는 없다.
에러 출력은 자격증명·응답 본문 유출을 피하기 위해 종류만 표시한다.
상세 진단은 비공개 계획/저널과 읽기 전용 DB/검색 조회로 수행한다.

- 미커밋 DB 작업은 롤백한다. 이미 커밋된 행은 자동으로 되돌리지 않는다.
- 기존 행 역보정이 필요하면 계획의 `before`와 journal을 대조하고, 현재 네 필드가 도구의
  예상 after 값 및 ID/유기번호와 여전히 일치할 때만 조건부 UPDATE를 별도 검토·승인한다.
  네 필드 밖의 변경이나 후속 사용자 상태 변경을 덮어쓰지 않는다. 역보정 후 검색도 재검증한다.
- 새 행은 사용자 참조가 생길 수 있어 자동 DELETE를 제공하지 않는다. 전진 보정 또는
  별도 승인한 참조 검증·제한적 복구를 사용한다.
- 이미지/코드 롤백은 데이터를 되돌리지 않는다. 인덱스 교체, 전체 삭제, 과거 배치 이력 조작은 금지한다.

## 검증 및 비용

적용 전후 대상 ID/유기번호, 네 필드, 기존 행의 설명/찜 수, 검색 벡터 hash,
alias/인덱스 UUID, 정기 배치 성공 기준일을 비교한다. 대상 외 ID 감소가 없어야 한다.
DB 전체 수의 차이는 실제 INSERTED 수로 설명해야 한다. 전체 건수 일치만으로 완전성을 주장하지 않는다.

APMS 호출은 월별 `ceil(reported/1000)`회, 이상 시 두 배(월당 최대 40회)다.
적용은 동물마다 DB 조회/쓰기 및 검색 대상 확인·GET·조건부 쓰기를 순차 수행한다.
월별 검토 대상만 처리하며 연간 전체 데이터를 5만 건 제한이 있는 정기 Job에 넣지 않는다.
최초 실행은 소량 후보 월/계획으로 소요 시간을 실측한 다음 큰 월의 시간을 산정한다.

```bash
# 서비스 디렉터리에서 Java 17 및 격리 ci 환경을 지정한 뒤 실행
bash ./gradlew test --tests '*HistoryPlanTest' --tests '*HistoryCollectorTest' --tests '*HistorySearchTest'
# 아래 opt-in 테스트는 전용 localhost:23316/pawbridge_ci, ES :23200 필요
HISTORY_REHEARSAL=true bash ./gradlew test --tests '*HistoryReconciliationIntegrationTest'
bash ./gradlew test bootJar
```

격리 리허설은 실제 MySQL 저장/ES 부분 갱신과 검색 장애 후 재개를 검증한다.
공급자 API는 테스트 fixture로 대체한다. 이는 운영 데이터 보정이나 최신 후보 전체 변환 검증을 대신하지 않는다.
