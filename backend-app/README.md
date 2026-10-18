# PawBridge 업무 통합 실행기

Java 21·Gradle 8.14.3·Spring Boot 3.4.11의 독립 멀티프로젝트다. F2의 게시글 단건 GET·작성자 닉네임 조회에 F3의 인증된 일반 게시글 작성을 편입한 후보이며 운영 Gateway를 이 실행기로 연결하거나 기존 서비스를 종료하지 않는다.

## 업무 경계와 호출

| 모듈 | 책임 | 직접 프로젝트 의존성 |
|---|---|---|
| boot | MVC·보안·DB·업무 구현 조립과 실행 JAR | user, community |
| user | 공개 닉네임 조회 계약과 JDBC 구현 | 없음 |
| community | 게시글 읽기·작성·미디어 업로드·검색 projection·REST 응답 | user |

```text
boot -> community.internal.post.web -> community.internal.post.application
                                      -> community.internal.post.infrastructure
                                      -> user.api -> user.internal.nickname.infrastructure
```

`api`는 업무 간 Java 계약이다. HTTP는 `internal.<기능>.web`, DB/JSON 변환은 infrastructure, 조회 조합은 application이 담당한다. 읽기 projection에 빈 domain 계층이나 회원 전체 aggregate를 만들지 않는다. F3의 domain은 일반 게시판 작성 가능 규칙과 저장 입력만 담당한다.

Community는 User의 공개 API만 참조한다. User 부재·명시된 조회 실패는 `사용자{id}`로 표시하고, 빈 HTTP 응답이 null로 decode되던 동작은 빈 저장 닉네임의 표시 호환에 반영한다. 공백은 그대로 둔다. 게시글 없음/soft-delete는 404이며 게시글 DB·JSON 오류는 닉네임 fallback로 숨기지 않는다.

공개 GET은 `/api/v1/posts/read/{postId}` 하나다. 기존 envelope와 data 9필드, 이미지 null/순서/중복과 LocalDateTime 의미를 보존한다. 기존 malformed ID의 generic 500 동작은 유지하며 400 개선을 섞지 않는다. `/api/posts/...` alias rewrite는 기존 Gateway 책임이다. 후보는 별도 nickname HTTP·로그인 API를 제공하지 않는다. F3는 같은 기존 경로 `POST /api/v1/posts`의 multipart 작성만 추가한다.

## DB·실행 설정

읽기용 DataSource·transaction manager와 쓰기용 DataSource·transaction manager를 명시적으로 구분한다. 읽기는 `pawbridge_community.posts`와 `pawbridge_user.users`의 필요한 열만 읽는다. Community 게시글 tx를 종료하고 연결을 반환한 뒤 User 닉네임 tx를 시작한다. application 조합에 외곽 tx나 REQUIRES_NEW를 두지 않는다. SQL의 schema와 parameter binding을 명시한다.

| 환경 변수 | 의미 |
|---|---|
| F2_JDBC_URL | 명시적 PostgreSQL JDBC URL. 계정/쿼리 옵션을 URL에 넣지 않음 |
| F2_DB_USER | 대상 두 schema의 필요한 열을 읽을 계정 |
| F2_DB_PASSWORD | 해당 계정 암호. 이미지/소스에 포함하지 않음 |
| F2_POOL_MAX_SIZE | 후보 시험 pool 상한, 기본 2; 운영 pool 확정값 아님 |
| F2_PORT | loopback HTTP 포트. 기본 0(임의 포트) |

필수 설정이 없으면 main은 Spring 기동/DB 접속 전에 실패한다. 운영 기본 주소나 기존 .env를 자동 탐색하지 않는다. readOnly tx와 SQL READ ONLY를 적용하며 CI 앱 계정도 SELECT-only로 제한한다. 앱 classpath에 SQL 초기화·Flyway·기존 migration/consumer/스케줄러·Feign·JPA·Redis·Kafka·ES·OAuth를 추가하지 않는다. F3의 S3 SDK는 명시적 endpoint·region·정적 credential만 사용한다. 기본 AWS credential/region 탐색 체인이나 기존 Secret 파일을 사용하지 않는다.

DB의 `TIMESTAMP(6)`와 기존 Hibernate UTC JDBC 추출 의미를 보존하도록 mapper를 비교한다. 문자열에 Z를 붙이거나 무조건 9시간을 더하지 않는다. 이 후보를 운영으로 연결하기 전에는 운영 DB 권한·기존/신규 JVM timezone·총 pool 예산과 라우팅 인계를 별도 검증해야 한다.

## F3 작성 계약과 별도 쓰기 연결

`POST /api/v1/posts`는 기존 `title`, `content`, `boardType`, 선택 `files` multipart 계약과 HTTP 200 envelope·응답 9필드를 유지한다. 검증된 Bearer JWT의 양수 `userId`만 사용하며 클라이언트의 작성자 헤더·파라미터를 신뢰하지 않는다. 기존 발급기의 access token·역할을 수용하고 refresh·위조·만료·잘못된 identity는 업로드·DB 작업 전에 401로 거부한다. 신규 로그인·토큰 발급·갱신은 구현하지 않는다.

일반 게시판 3종만 작성하고 MISSING/REPORT는 업로드 전에 전용 등록 안내로 거부한다. 기존에 적용되지 않던 NotBlank 등 새 입력 규칙은 추가하지 않는다. 작성 순서는 게시판 검증 → S3 업로드 → 독립된 짧은 User 닉네임 조회 → writer 트랜잭션 → 응답이다. 예상 밖 닉네임 오류가 DB commit 뒤에 발생하지 않도록 기존 HTTP 호출 순서를 조정했다. User 부재·명시적 unavailable fallback은 F2와 같으며, 닉네임과 게시글의 동일 시점 snapshot을 보장하지 않는다.

writer는 같은 명시적 JDBC URL에 별도 계정·pool max1/minIdle0으로 연결한다. reader 기본 max2와 합쳐 후보 기본 상한은 3이며 운영 pool 예산을 확정한 수치는 아니다. 두 연결·JDBC·TM은 qualifier로 조립한다. application 외곽 트랜잭션·REQUIRES_NEW·XA는 없다. `posts`, `outbox_events`, `post_search_documents`의 3개 쓰기는 writer의 단일 트랜잭션에 참여한다. 기존 POST_CREATED JSON·Nori 9.12.3·검색 가중치·UTC JDBC 바인딩 의미를 보존한다. 검색 조회나 이벤트 소비자는 추가하지 않는다.

| 추가 필수 환경 변수 | 의미 |
|---|---|
| F3_DB_WRITER_USER / F3_DB_WRITER_PASSWORD | reader와 다른 최소 쓰기 계정. User 쓰기·DDL·소유권 권한을 부여하지 않음 |
| F3_JWT_SECRET | 기존 발급기 검증용 UTF-8 HMAC 키. 출력·이미지 포함 금지 |
| F3_STORAGE_ENDPOINT / F3_STORAGE_REGION | 명시적인 S3 호환 endpoint와 region |
| F3_STORAGE_BUCKET / F3_STORAGE_PUBLIC_BASE_URL | 업로드 bucket과 반환 URL 기준 |
| F3_STORAGE_OBJECT_PREFIX | 이번 후보에 허용한 비어 있지 않은 객체 prefix |
| F3_STORAGE_ACCESS_KEY_ID / F3_STORAGE_SECRET_ACCESS_KEY | 해당 저장소에 한정한 실행 시 인증값 |

설정은 Spring/listener/외부 client 기동 전에 검사한다. multipart 한도는 파일 100MB·요청 200MB다. 선택 파일의 순서·MIME·확장자·URL 의미를 유지하고 stream을 닫는다. 업로드 일부 실패 또는 뒤이은 DB 실패 시 이미 업로드한 객체가 남는 기존 정책을 유지한다. 자동 POST 재시도·객체 삭제·idempotency·보상 큐를 추가하지 않는다. commit 결과가 불명확한 경우에도 업로드 객체를 임의 삭제하지 않는다. 이 후보의 시험 성공은 실제 R2 연결·운영 전환 증거가 아니다.

## 구조 검사

`gradle/architecture.gradle`의 `verifyArchitecture`와 `verifyBusinessPackages`를 유지한다.

- 루트는 집계만, boot만 실행 JAR, user/community는 java-library다.
- 세 모듈 DAG·순환/boot 역참조·외부 source/resource/compiler input·class output 소유권을 검사한다.
- api에는 JDK/허용 api만 노출하며 타 업무 internal 접근과 내부 타입 signature 누출을 거부한다.
- domain은 자기 domain과 기술을 제외한 JDK만 허용한다. application은 web/infrastructure/JPA/JDBC/HTTP/Feign/TransactionTemplate을 참조하지 않는다. Spring 선언 예외는 Transactional/Propagation/Isolation뿐이다.
- application 검사는 명시 기술 blacklist이며 임의 외부 라이브러리의 모든 의미를 판정하는 샌드박스는 아니다. 리플렉션·업무 책임도 코드 리뷰한다.
- ArchUnit은 빌드 도구이며 앱 runtime에 포함하지 않는다. Kotlin 도입 시 별도 compiled fixture로 언어 runtime 경계를 검증한다.

## GitHub-hosted 검증과 oracle

운영 Mac에서는 Gradle·Java 앱·컨테이너를 실행하지 않는다. `Backend app check`는 backend-app/전용 workflow의 dev PR·dev push에만 GitHub-hosted Linux에서 실행하며 contents:read와 credential 미보존을 유지한다. 이미지 발행·infra PR·배포는 없다.

1. 세 모듈 unit/MVC/Security·구조 fixture와 실행 JAR를 검사한다.
2. digest로 고정한 공식 PostgreSQL 17.11 linux/amd64 컨테이너를 고유 run label로 생성한다. loopback bind·CPU 2·RAM 512MiB·tmpfs 제한과 합성 schema/SELECT-only reader 계정을 사용한 뒤, 같은 DB에 F3 3종 테이블·identity·최소 writer·run marker만 확장한다. 운영 migration/owner/ACL을 변경하는 절차가 아니다.
3. compatibilityTest와 compatibilitySeoulTest를 별도 UTC/Asia-Seoul JVM에서 실행한다. 같은 저장행의 legacy Hibernate/Jackson과 새 JDBC, 실제 nickname decoder, pool max1·실제 권한 실패/회복·읽기 전용을 검사한다.
4. 실제 JAR와 run 전용 S3 HTTP stub을 loopback에 띄워 기존 GET 회귀와 실제 발급기 JWT → multipart POST → GET을 확인한다. SDK가 전송한 bytes/MIME/순서, 인증·게시판 거부의 무부작용, Outbox/search 원자성, 권한·부분 업로드 실패 후 복구, 객체 유지·미삭제, 두 pool의 SIGTERM 종료·연결 해제를 검사한다. 필수 설정 누락도 listener·외부 접속 전에 거부하는지 확인한다.
5. always cleanup은 현재 run owner label의 합성 컨테이너만 정지/제거한다. 일반 prune·다른 컨테이너/볼륨 조작은 하지 않는다.

`scripts/f2_ci.py`·`f3_ci.py`·`f3_s3_stub.py`의 실행 가드는 이 저장소 GitHub-hosted Linux와 고유 run ID를 요구한다. DB mutation 전에는 loopback/정확한 시험 DB/marker/admin/reader를 대조한다. 미설정 테스트를 skip 성공으로 처리하지 않는다. `scripts/f2_fixture.sql`·`f3_fixture.sql`은 CI 전용이며 앱 resource/JAR에 포함되지 않는다.

`prepare_legacy_oracle.py`는 기준 `e8ad7b8ec9006772ccc5f5881334df2730dbd8de`의 정확히 6개 Java 파일만 해시 검증해 boot/build 안 test-only 경로로 추출한다. Post/BoardType/StringListConverter/PostResponse와 UserServiceClient 및 그 JDK-only 응답 의존 PrivateNoteModels다. legacy 전체 sourceDir·Application을 편입하지 않는다. 실제 source/버전 비교는 compatibility classpath에서만 이뤄지고 앱 JAR에는 들어가지 않는다.

`prepare_f3_legacy_oracle.py`는 F3 기준 `0e0161e03ddb31981487c4d0ece280cb04c1107c`의 기존 JWT 발급기·User 의존 타입·Outbox·Nori 등 정확히 9개 파일을 해시 검증해 test-only 경로로 추출한다. 실제 JPA/발급기/분석기와 후보의 차이를 UTC·Seoul 양쪽에서 비교한다. 생성한 합성 토큰은 run 전용 0600 임시 파일에만 두고 artifact에서 제외한 뒤 정리한다.

TestKit은 Gradle logging 충돌을 막기 위해 architectureTest 전용 classpath를 사용한다. 실제 업무 build와 guard를 fixture에 복사하고, 본 빌드가 해결한 BOM/의존 cache를 offline으로 사용한다. 허용/금지 fixture의 이유를 검사하며 일반 컴파일·다운로드 오류를 구조 차단 성공으로 취급하지 않는다.

Wrapper checksum 고정과 기존 최소 메모리·worker 1 설정을 유지한다. CI는 테스트 XML·source/runtime·oracle hash·packaged JAR hash/기동/종료를 7일 보존한다. Mac ARM64 운영 기동·전체 서비스 통합·RAM 절감의 증거와 구분한다.

전체 결정·계획·문제 해결 기록은 Obsidian `Projects/pawbridge`의 F2·F3 계획과 모듈형 모놀리스 전환 노트를 따른다.
