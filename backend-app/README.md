# PawBridge 업무 통합 실행기

Java 21·Gradle 8.14.3·Spring Boot 3.4.11의 독립 멀티프로젝트다. F2는 기존 게시글 단건 GET과 작성자 닉네임 조회만 편입한 후보이며 운영 Gateway를 이 실행기로 연결하거나 기존 서비스를 종료하지 않는다.

## 업무 경계와 호출

| 모듈 | 책임 | 직접 프로젝트 의존성 |
|---|---|---|
| boot | MVC·보안·DB·업무 구현 조립과 실행 JAR | user, community |
| user | 공개 닉네임 조회 계약과 JDBC 구현 | 없음 |
| community | 게시글 읽기·닉네임 조합·REST 응답 | user |

```text
boot -> community.internal.post.web -> community.internal.post.application
                                      -> community.internal.post.infrastructure
                                      -> user.api -> user.internal.nickname.infrastructure
```

`api`는 업무 간 Java 계약이다. HTTP는 `internal.<기능>.web`, DB/JSON 변환은 infrastructure, 조회 조합은 application이 담당한다. 이번 읽기 projection에 빈 domain 계층이나 회원 전체 aggregate를 만들지 않는다. 필요해지는 domain 규칙은 후속 업무 편입에서 추가한다.

Community는 User의 공개 API만 참조한다. User 부재·명시된 조회 실패는 `사용자{id}`로 표시하고, 빈 HTTP 응답이 null로 decode되던 동작은 빈 저장 닉네임의 표시 호환에 반영한다. 공백은 그대로 둔다. 게시글 없음/soft-delete는 404이며 게시글 DB·JSON 오류는 닉네임 fallback로 숨기지 않는다.

공개 GET은 `/api/v1/posts/read/{postId}` 하나다. 기존 envelope와 data 9필드, 이미지 null/순서/중복과 LocalDateTime 의미를 보존한다. 기존 malformed ID의 generic 500 동작은 유지하며 400 개선을 섞지 않는다. `/api/posts/...` alias rewrite는 기존 Gateway 책임이다. 후보는 별도 nickname HTTP·쓰기·로그인 API를 제공하지 않는다.

## DB·실행 설정

DataSource·transaction manager 각 1개로 `pawbridge_community.posts`와 `pawbridge_user.users`의 필요한 열만 읽는다. Community 게시글 tx를 종료하고 연결을 반환한 뒤 User 닉네임 tx를 시작한다. application 조합에 외곽 tx나 REQUIRES_NEW를 두지 않는다. SQL의 schema와 parameter binding을 명시한다.

| 환경 변수 | 의미 |
|---|---|
| F2_JDBC_URL | 명시적 PostgreSQL JDBC URL. 계정/쿼리 옵션을 URL에 넣지 않음 |
| F2_DB_USER | 대상 두 schema의 필요한 열을 읽을 계정 |
| F2_DB_PASSWORD | 해당 계정 암호. 이미지/소스에 포함하지 않음 |
| F2_POOL_MAX_SIZE | 후보 시험 pool 상한, 기본 2; 운영 pool 확정값 아님 |
| F2_PORT | loopback HTTP 포트. 기본 0(임의 포트) |

필수 설정이 없으면 main은 Spring 기동/DB 접속 전에 실패한다. 운영 기본 주소나 기존 .env를 자동 탐색하지 않는다. readOnly tx와 SQL READ ONLY를 적용하며 CI 앱 계정도 SELECT-only로 제한한다. 앱 classpath에 SQL 초기화·Flyway·기존 migration/consumer/스케줄러·Feign·JPA·Redis·Kafka·ES·R2·OAuth를 추가하지 않는다.

DB의 `TIMESTAMP(6)`와 기존 Hibernate UTC JDBC 추출 의미를 보존하도록 mapper를 비교한다. 문자열에 Z를 붙이거나 무조건 9시간을 더하지 않는다. 이 후보를 운영으로 연결하기 전에는 운영 DB 권한·기존/신규 JVM timezone·총 pool 예산과 라우팅 인계를 별도 검증해야 한다.

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
2. digest로 고정한 공식 PostgreSQL 17.11 linux/amd64 컨테이너를 고유 run label로 생성한다. loopback bind·CPU 2·RAM 512MiB·tmpfs 제한과 합성 schema/SELECT-only 계정을 사용한다.
3. compatibilityTest와 compatibilitySeoulTest를 별도 UTC/Asia-Seoul JVM에서 실행한다. 같은 저장행의 legacy Hibernate/Jackson과 새 JDBC, 실제 nickname decoder, pool max1·실제 권한 실패/회복·읽기 전용을 검사한다.
4. 실제 JAR를 loopback에 띄워 HTTP·권한 실패 fallback·같은 pool의 다음 요청 회복·SIGTERM 종료와 연결 해제를 확인한다. 무설정 JAR 거부도 별도로 확인한다.
5. always cleanup은 현재 run owner label의 합성 컨테이너만 정지/제거한다. 일반 prune·다른 컨테이너/볼륨 조작은 하지 않는다.

`scripts/f2_ci.py`의 실행 가드는 이 저장소 GitHub-hosted Linux와 고유 run ID를 요구한다. DB mutation 전에는 loopback/정확한 시험 DB/marker/admin/reader를 대조한다. 미설정 테스트를 skip 성공으로 처리하지 않는다. `scripts/f2_fixture.sql`은 CI 전용이며 앱 resource/JAR에 포함되지 않는다.

`prepare_legacy_oracle.py`는 기준 `e8ad7b8ec9006772ccc5f5881334df2730dbd8de`의 정확히 6개 Java 파일만 해시 검증해 boot/build 안 test-only 경로로 추출한다. Post/BoardType/StringListConverter/PostResponse와 UserServiceClient 및 그 JDK-only 응답 의존 PrivateNoteModels다. legacy 전체 sourceDir·Application을 편입하지 않는다. 실제 source/버전 비교는 compatibility classpath에서만 이뤄지고 앱 JAR에는 들어가지 않는다.

TestKit은 Gradle logging 충돌을 막기 위해 architectureTest 전용 classpath를 사용한다. 실제 업무 build와 guard를 fixture에 복사하고, 본 빌드가 해결한 BOM/의존 cache를 offline으로 사용한다. 허용/금지 fixture의 이유를 검사하며 일반 컴파일·다운로드 오류를 구조 차단 성공으로 취급하지 않는다.

Wrapper checksum 고정과 기존 최소 메모리·worker 1 설정을 유지한다. CI는 테스트 XML·source/runtime·oracle hash·packaged JAR hash/기동/종료를 7일 보존한다. Mac ARM64 운영 기동·전체 서비스 통합·RAM 절감의 증거와 구분한다.

전체 결정·계획·문제 해결 기록은 Obsidian `Projects/pawbridge`의 F2 계획과 모듈형 모놀리스 전환 노트를 따른다.
