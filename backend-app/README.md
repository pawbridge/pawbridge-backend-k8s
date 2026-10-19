# PawBridge 업무 통합 실행기

Java 21·Gradle 8.14.3·Spring Boot 3.4.11의 독립 멀티프로젝트다. F2의 게시글 단건 GET·작성자 닉네임 조회, F3의 인증된 일반 게시글 작성, F4의 본인 게시글 삭제, F5의 본인 게시글 수정, F6의 공개 게시글 목록, F7의 공개 게시글 검색, F8의 공개 댓글 목록을 편입하는 후보이며 운영 Gateway를 이 실행기로 연결하거나 기존 서비스를 종료하지 않는다.

## 업무 경계와 호출

| 모듈 | 책임 | 직접 프로젝트 의존성 |
|---|---|---|
| boot | MVC·보안·DB·업무 구현 조립과 실행 JAR | user, community |
| user | 공개 닉네임 조회 계약과 JDBC 구현 | 없음 |
| community | 게시글 단건·목록·검색·작성·수정·삭제·댓글 목록·미디어·검색 projection·REST 응답 | user |

```text
boot -> community.internal.post.web -> community.internal.post.application
                                      -> community.internal.post.infrastructure
                                      -> user.api -> user.internal.nickname.infrastructure
```

`api`는 업무 간 Java 계약이다. HTTP는 `internal.<기능>.web`, DB/JSON 변환은 infrastructure, 조회 조합은 application이 담당한다. 읽기 projection에 빈 domain 계층이나 회원 전체 aggregate를 만들지 않는다. F3의 domain은 일반 게시판 작성 가능 규칙과 저장 입력만 담당한다.

Community는 User의 공개 API만 참조한다. User 부재·명시된 조회 실패는 `사용자{id}`로 표시하고, 빈 HTTP 응답이 null로 decode되던 동작은 빈 저장 닉네임의 표시 호환에 반영한다. 공백은 그대로 둔다. 게시글 없음/soft-delete는 404이며 게시글 DB·JSON 오류는 닉네임 fallback로 숨기지 않는다.

공개 게시글 GET은 `/api/v1/posts/read/{postId}` 단건, `/api/v1/posts/read` 목록, `/api/v1/posts/search?keyword=...` 검색이다. 기존 envelope와 data 9필드, 이미지 null/순서/중복과 LocalDateTime 의미를 보존한다. 공개 댓글 GET `/api/v1/comments/posts/read/{postId}`는 아래 F8의 7필드 배열 계약을 따른다. 기존 malformed ID의 generic 500 동작은 유지하며 400 개선을 섞지 않는다. `/api/posts/...` alias rewrite는 기존 Gateway 책임이다. 후보는 별도 nickname HTTP·로그인 API를 제공하지 않는다. F3는 같은 기존 경로 `POST /api/v1/posts`의 multipart 작성만 추가한다.

## DB·실행 설정

읽기용 DataSource·transaction manager와 쓰기용 DataSource·transaction manager를 명시적으로 구분한다. 읽기는 `pawbridge_community.posts`, `pawbridge_community.comments`, `pawbridge_community.post_search_documents`, `pawbridge_user.users`의 필요한 열만 읽는다. Community 게시글·댓글 tx를 종료하고 연결을 반환한 뒤 User 닉네임 tx를 시작한다. application 조합에 외곽 tx나 REQUIRES_NEW를 두지 않는다. SQL의 schema와 parameter binding을 명시한다.

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

writer는 같은 명시적 JDBC URL에 별도 계정·pool max1/minIdle0으로 연결한다. reader 기본 max2와 합쳐 후보 기본 상한은 3이며 운영 pool 예산을 확정한 수치는 아니다. 두 연결·JDBC·TM은 qualifier로 조립한다. application 외곽 트랜잭션·REQUIRES_NEW·XA는 없다. `posts`, `outbox_events`, `post_search_documents`의 3개 쓰기는 writer의 단일 트랜잭션에 참여한다. 기존 POST_CREATED JSON·Nori 9.12.3·검색 가중치·UTC JDBC 바인딩 의미를 보존한다. F3 범위에는 검색 조회나 이벤트 소비자를 추가하지 않으며 검색 조회는 아래 F7 계약으로 구분한다.

| 추가 필수 환경 변수 | 의미 |
|---|---|
| F3_DB_WRITER_USER / F3_DB_WRITER_PASSWORD | reader와 다른 최소 쓰기 계정. User 쓰기·DDL·소유권 권한을 부여하지 않음 |
| F3_JWT_SECRET | 기존 발급기 검증용 UTF-8 HMAC 키. 출력·이미지 포함 금지 |
| F3_STORAGE_ENDPOINT / F3_STORAGE_REGION | 명시적인 S3 호환 endpoint와 region |
| F3_STORAGE_BUCKET / F3_STORAGE_PUBLIC_BASE_URL | 업로드 bucket과 반환 URL 기준 |
| F3_STORAGE_OBJECT_PREFIX | 이번 후보에 허용한 비어 있지 않은 객체 prefix |
| F3_STORAGE_ACCESS_KEY_ID / F3_STORAGE_SECRET_ACCESS_KEY | 해당 저장소에 한정한 실행 시 인증값 |

설정은 Spring/listener/외부 client 기동 전에 검사한다. multipart 한도는 파일 100MB·요청 200MB다. 실제 application.properties를 로드한 boot 시험에서 두 한도의 Servlet 바인딩을 보호한다. 선택 파일의 순서·MIME·확장자·URL 의미를 유지하고 stream을 닫는다. 업로드 일부 실패 또는 뒤이은 DB 실패 시 이미 업로드한 객체가 남는 기존 정책을 유지한다. 자동 POST 재시도·객체 삭제·idempotency·보상 큐를 추가하지 않는다. commit 결과가 불명확한 경우에도 업로드 객체를 임의 삭제하지 않는다. 이 후보의 시험 성공은 실제 R2 연결·운영 전환 증거가 아니다.

예상 밖 작성 오류는 기존 500 응답을 유지하며, 서버 로그에는 예외 종류와 제한된 코드 위치만 남긴다. 예외 메시지·cause/suppressed 메시지·원본 Throwable·요청값·인증값·업로드 파일명·서명 URL은 로그 인자로 전달하지 않는다. 원인은 최대4개, 원인별 코드 위치는 최대6개, 진단 문자열은 최대4096자로 제한한다. 상세 SQL/SDK 메시지를 보존하는 로그가 아니므로 이 메타데이터만으로 장애 원인을 확정할 수는 없다.

## F4 본인 삭제와 파일 실패 경계

`DELETE /api/v1/posts/{postId}`는 기존 JWT의 작성자 본인만 허용한다. ADMIN도 일반 경로에서 다른 작성자의 글을 삭제할 수 없다. 성공은 HTTP/code200·`게시글이 삭제되었습니다.`·data:null이며, 부재/재삭제는404, 타인은403이다. 이 경로의 잘못된 숫자 타입은 기존500을 보존한다. legacy advice의 TypeMismatch import는 Hibernate 타입이므로 Spring 경로 변환 실패를 처리하는400 경로가 아니다. POST 작성 제한과 달리 기존5개 게시판 모두 삭제 대상이며 관리자 전용 삭제·별도 제보 삭제는 추가하지 않는다.

짧은 reader 조회와 소유권 확인이 끝난 뒤 연결을 반환하고, 저장된 URL의 미디어 삭제를 시도한다. writer는 `FOR UPDATE`로 미삭제 상태·소유자를 다시 확인하고 조건부 soft delete·updatedAt·정확한 POST_DELETED Outbox·검색 문서 삭제를 한 트랜잭션으로 처리한다. soft delete는 본문·작성자·생성일·미디어 URL·댓글/쪽지를 물리 삭제하지 않는다. 같은 글의 경쟁 요청은 하나만 DB 삭제·이벤트를 커밋하고 나머지는404가 된다. 외부 미디어 시도는 경쟁 요청 양쪽에서 먼저 발생할 수 있다.

미디어는 명시 public-base·bucket·object-prefix 안의 DB 저장 URL만 처리한다. 업로더가 생성하는 `posts/images|videos/UUIDv4`와 마지막 점부터의 suffix는 원문 객체 key로 인식해 SDK에 그대로 전달한다. 이 경우 `%`, `?`, `#`, 구분자·제어문자를 URL로 재해석하거나 디코딩하지 않으며, 일반 key에는 기존 경로·인코딩 우회 방지 가드를 유지한다. URL별 검증/SDK 실패 후에도 다음 URL과 DB 처리를 계속하며 null·중복·순서를 임의 정규화하지 않는다. 파일 삭제 성공 후 DB rollback이 확정되면 파일은 복원되지 않고, 저장소 실패 후 DB 삭제가 성공하면 파일이 남을 수 있다. commit 결과 불명은 원래 오류로 전달하며 새 자동 재시도·객체 복원·보상 큐를 추가하지 않는다. SDK의 기존 같은 key 재시도와 새 애플리케이션 재처리는 구분한다. 오류 로그에는 URL·키·예외 메시지·원본 Throwable을 전달하지 않는다.

기존 reader max2/writer max1과 F3 설정을 재사용한다. F4 단계는 CI writer에 `UPDATE(deleted_at,updated_at)` 및 검색 문서 DELETE를 보완했다. F5는 같은 역할에 title/content/image_urls 수정만 추가하며 posts 물리 DELETE·작성자/게시판/생성일 수정·User 쓰기·DDL은 계속 거부한다. 실제 운영 역할/ACL·라우팅·R2 권한은 이 후보가 변경하지 않는다. 외부 I/O 중 연결 미점유와 실제 두 연결의 행잠금은 별도 합성 계약으로 검증하며 pool1의 대기를 행잠금 근거로 사용하지 않는다.

## F5 본인 수정과 첨부 교체

`PUT /api/v1/posts/{postId}`는 선택 `title`, `content`, `files` multipart와 기존200 envelope·응답9필드를 유지한다. 검증된 작성자 본인만 허용하고 ADMIN도 타인의 글을 수정할 수 없다. F3 작성 제한을 가져오지 않으므로 기존5개 게시판 모두 수정 대상이다. title/content 생략은 현재 값을 유지하고 명시 빈 문자열은 그대로 전달한다. files 미전달은 유지, 빈 파일 한 개는 기존 첨부를 삭제하고 빈 목록으로 교체하는 요청이다. 새 웹 입력 제약을 추가하지 않으며 DB 길이 위반은 기존500, 명시된 문자 rollback 제약만400으로 매핑한다.

reader 조회·소유권 확인 → 필요할 때 기존 파일 삭제 시도 → 새 업로드 → 닉네임 조회 → 짧은 writer tx 순서다. 외부 미디어·닉네임 I/O 중 두 DB 연결을 점유하지 않는다. writer는 `FOR UPDATE`로 미삭제 상태·소유자를 다시 확인하고 누락 필드는 잠근 현재 행에서 가져온다. 이로써 경쟁 수정의 다른 필드가 사라지거나 삭제된 글이 되살아나지 않는다. 외부 업로드 뒤의 재확인에서404/403이 발생할 수 있으며 이미 삭제한 파일 소실·새 객체 잔류는 기존 한계다. 새 자동 보상·애플리케이션 재시도는 넣지 않는다.

실제 legacy JPA dirty checking·auditing·응답 생성 순서를 PostgreSQL에서 비교한다. 무변경/동일값 PUT은 행과 updatedAt을 바꾸지 않아도 `POST_UPDATED`와 검색 투영을 갱신한다. 값이 바뀐 PUT의 응답 updatedAt은 commit 전의 이전 값이며, 후속 GET은 감사 처리한 새 시각을 반환하는 기존 차이를 보존한다. SQL NULL 이미지는 빈 목록으로 읽지만 JSON literal null은 text-only 수정의 payload 생성에서500으로 rollback하는 기존 동작을 유지한다. 첨부 교체가 성공하면 빈 목록 또는 새 URL 목록으로 바꿀 수 있다.

게시글 변경·정확한6필드 POST_UPDATED·Nori 검색 투영은 기존 writer 한 트랜잭션이다. reader max2/writer max1·설정·S3 client를 재사용하며 운영 schema/역할/ACL을 변경하지 않는다. 동일 필드/다른 필드 PUT 경합과 PUT/DELETE 양 순서는 독립 PG 연결·실제 blocking PID로 검증한다. 실패 응답과 로그의 인증값·예외 메시지 제외 정책은 F3/F4와 같다.

첨부 파싱은 컨트롤러 선택 전에 실패할 수 있으므로, `MultipartException`만 받는 공통 advice를 boot에서 명시적으로 조립한다. POST/PUT의 잘못된 multipart·용량 초과는 기존500/code/message/data 응답을 유지하고 로그에는 예외 클래스만 남긴다. 일반 업무/인증 오류의 담당,100MB/200MB 한도와 기본 eager parsing은 그대로다. 실제 resolver의 `getParts()` 실패를 주입한 회귀에서 handler=null·무토큰401/파싱0회·인증 후 안전한500·DB 연결0을 확인한다. 이 시험은 실제 대용량 바이트 전송이나 운영 저장소 시험이 아니다.

## F6 공개 목록 계약

`GET /api/v1/posts/read`는 비로그인 공개 API이며 기존 200 envelope의 `data`에 9필드 게시글 배열을 반환한다. 빈 결과는 `[]`다. `page`·`size`·`boardType` 쿼리로 서버 필터나 페이지를 만들지 않는다. 기존 프런트가 전체 배열에서 게시판과 화면 페이지를 계산하므로 응답을 임의로 줄이지 않는다. 관리자 Page·검색 readiness/순위/10건 제한은 이 계약과 별개다.

기존 `JdbcPostReader`는 같은 reader 연결·행 mapper로 미삭제 게시글을 한 번 조회하고 `created_at DESC` 순서로 전부 매핑한 뒤 tx와 연결을 반환한다. 같은 생성 시각의 ID 보조 정렬은 추가하지 않는다. 이후 application이 행 순서대로 `UserNicknameQuery`를 호출하며 같은 작성자도 행마다 조회한다. User 테이블을 Community SQL에 JOIN하거나 nickname cache·새 pool·새 읽기 권한을 추가하지 않는다.

닉네임은 F2의 명시된 부재·조회 실패만 `사용자{id}`로 표시하고 빈 닉네임의 null 호환·공백은 유지한다. 옛 HTTP helper의 넓은 `catch(Exception)`과 같은 예외 범위라고 주장하지 않으며, 예상 밖 nickname 오류나 게시글 SQL/JSON 매핑 실패는 목록 전체의 안전한500으로 처리한다. 실패 행 생략·부분200·오류를 빈 배열로 숨기는 처리는 없다. SQL NULL 이미지→빈 배열·JSON literal null→null과 기존 날짜 의미도 재사용한다.

exact 목록 GET만 보안 허용 목록에 추가한다. 공개 목록은 불필요하게 Bearer 인증을 요구하지 않으며 기존 단건·쓰기 보안과 오류 처리는 유지한다. 실행기 자체는 여전히 F3의 writer/JWT/S3 설정을 요구한다. 전체 배열·행별 닉네임의 운영 데이터량에 대한 성능·메모리 수용성이나 구 서비스 종료는 이 후보의 기능 검증과 구분한다.

## F7 공개 게시글 검색

`GET /api/v1/posts/search?keyword=...`는 기존 공개 검색의 200 envelope·9필드 배열을 유지한다. 전체 게시판에서 `ts_rank DESC`, `created_at DESC`, `post_id DESC` 순서로 최대10건을 반환한다. 기존 프런트가 그 결과에 게시판 필터와 화면 페이지를 적용하므로 서버 필터·Page·count·score 필드를 추가하지 않는다.

필수 `keyword` 누락은 기존 메시지의400이다. 빈 문자열·공백은 길이 검사보다 먼저 빈 배열로 처리하며, Nori 분석 후 토큰이 없는 경우도 검색 트랜잭션·DB·닉네임 조회를 하지 않는다. 비공백 입력의 UTF-16 길이200 초과·NUL·분석 후 중복 제거 단어32개 초과는400이다. Nori9.12.3과 `nori-9.12.3-v1`, 중복 제거·OR 검색·tsquery escaping을 보존한다. writer의 document 토큰·생명주기는 변경하지 않는다.

검색은 모든 미삭제 게시글의 projection 존재·제목/본문 hash·analyzer version을 같은 SQL statement의 readiness/ranked CTE로 확인한다. 매칭되지 않는 글 하나가 stale이어도503이며 삭제 글의 stale 자료는 제외된다. GET은 검색 문서를 재구축하거나 데이터를 보정하지 않는다. SQL/연결 획득·검색 읽기 트랜잭션 시작/시간초과는503, 잘못된 저장 JSON·enum과 예상 밖 오류는500이다. 검색어·본문·URL·인증값·원예외 메시지를 로그에 남기지 않는다.

검색 날짜는 legacy PostgreSQL 검색의 `getObject(LocalDateTime.class)` 의미를 보존한다. 단건·전체 목록의 Hibernate/UTC 호환 mapper를 무조건 재사용하지 않는다. 검색 SELECT 전체를 매핑하고 연결을 반환한 뒤 기존 User 공개 계약으로 닉네임을 순차 조회한다. F2/F6의 명시된 unavailable fallback과 빈닉네임 null 호환을 유지하며 legacy 원격 helper의 넓은 catch와 완전히 같은 예외 범위라고 표현하지 않는다.

검색용 JDBC·읽기 트랜잭션 timeout은 각각5초이며 기존 reader DataSource와 transaction manager를 사용한다. 단건·목록의3초와 기존 두 pool 구성은 유지한다. 5초는 전체 HTTP 응답시간 SLA가 아니며 Top10도 전역 readiness의 DB 비용 상한을 보장하지 않는다. 실행기는 여전히 기존 writer/JWT/S3 설정이 필요하다.

합성 CI fixture에는 기존 reader의 `post_search_documents(post_id,title_hash,content_hash,analyzer_version,tokens)` SELECT만 추가한다. 새 schema·index·계정·pool·설치와 운영 권한 변경은 없다. 운영 후보 인계 시 실제 읽기 계정 권한·전체 데이터 성능·단일 작업 소유권과 Gateway 라우팅은 별도 확인해야 한다. 이 기능의 CI 성공을 운영 전환·검색 전체 인계 완료로 해석하지 않는다.

## F8 공개 댓글 목록

`GET /api/v1/comments/posts/read/{postId}`는 비로그인 공개 API다. 기존 200 envelope의 `data`에 `commentId`, `postId`, `authorId`, `authorNickname`, `content`, `createdAt`, `updatedAt`의 정확히 7필드 배열을 반환하며 빈 결과는 `[]`다. `page`·`size` 등으로 결과를 줄이거나 작성자 헤더로 필터하지 않는다. 댓글 생성·수정·삭제 경로와 다른 메서드는 공개하지 않는다.

같은 reader의 짧은 읽기 전용 트랜잭션에서 해당 `post_id`의 미삭제 댓글을 `created_at DESC`로 모두 조회한다. 같은 생성 시각에서 ID 순서를 새 보장으로 추가하지 않는다. 부모 게시글 존재·삭제 상태를 조회하거나 JOIN하지 않으므로 부모가 soft delete되어도 미삭제 댓글은 보인다. 게시글이 없고 댓글도 없으면200·빈 배열이며, 부모 없는 저장 댓글도 기존 조회처럼 반환한다. 날짜는 실제 legacy Hibernate의 UTC JDBC 추출을 UTC·Seoul JVM에서 비교한다.

전체 댓글 행을 매핑하고 연결을 반환한 뒤 `UserNicknameQuery`로 순차 조회한다. 같은 작성자도 행마다 조회하며 cache·추가 pool·User SQL JOIN은 없다. 명시된 사용자 부재·조회 불가만 `사용자{id}`로 처리하고 빈 닉네임의 null 호환과 공백을 보존한다. legacy 원격 helper의 넓은 `catch(Exception)`을 그대로 재현했다고 주장하지 않는다. 댓글 SQL·행 매핑·예상 밖 닉네임 오류는 전체 안전한500이며, 부분200·오류 행 생략·실패를 빈 배열로 숨기는 처리는 없다.

실행기에는 기존 두 pool과 writer/JWT/S3 필수 설정이 유지된다. 합성 CI에서만 소유권 marker를 확인한 기존 PostgreSQL에 실제 baseline과 같은 댓글 표와 reader의 7개 SELECT 열을 추가한다. F4의 별도 FK 댓글·쪽지 fixture는 보존하며 운영 스키마·권한이나 댓글 쓰기 권한을 변경하지 않는다. 전체 댓글·행별 닉네임의 운영 데이터량 성능과 Gateway 전환은 별도 검증 대상이다.

## 구조 검사

`gradle/architecture.gradle`의 `verifyArchitecture`와 `verifyBusinessPackages`를 유지한다.

- 루트는 집계만, boot만 실행 JAR, user/community는 java-library다.
- 세 모듈 DAG·순환/boot 역참조·외부 source/resource/compiler input·class output 소유권을 검사한다.
- api에는 JDK/허용 api만 노출하며 타 업무 internal 접근과 내부 타입 signature 누출을 거부한다.
- domain은 자기 domain과 기술을 제외한 JDK만 허용한다. application은 web/infrastructure/JPA/JDBC/HTTP/Feign/TransactionTemplate을 참조하지 않는다. Spring 선언 예외는 Transactional/Propagation/Isolation뿐이다.
- application 검사는 명시 기술 blacklist이며 임의 외부 라이브러리의 모든 의미를 판정하는 샌드박스는 아니다. 리플렉션·업무 책임도 코드 리뷰한다.
- ArchUnit은 빌드 도구이며 앱 runtime에 포함하지 않는다. Kotlin 도입 시 별도 compiled fixture로 언어 runtime 경계를 검증한다.

## GitHub-hosted 검증과 oracle

로컬 단위·컴파일 검사는 운영 서비스·DB에 연결하지 않는다. 실제 PostgreSQL/S3 HTTP 통합 검증은 아래 GitHub-hosted 전용 가드 안에서만 실행한다. `Backend app check`는 dev PR·push마다 경로 선택과 고정 최종 결과를 보고하고, backend-app·관련 CI 변경이 선택됐을 때 GitHub-hosted Linux에서 앱 검증을 실행한다. contents:read와 credential 미보존을 유지한다. 이미지 발행·infra PR·배포는 없다.

1. 세 모듈 unit/MVC/Security·구조 fixture와 실행 JAR를 검사한다.
2. digest로 고정한 공식 PostgreSQL 17.11 linux/amd64 컨테이너를 고유 run label로 생성한다. loopback bind·CPU 2·RAM 512MiB·tmpfs 제한과 합성 schema/SELECT-only reader 계정을 사용한 뒤, 같은 DB에 F3 3종 테이블·identity·최소 writer·run marker만 확장한다. 운영 migration/owner/ACL을 변경하는 절차가 아니다.
3. compatibilityTest와 compatibilitySeoulTest를 별도 UTC/Asia-Seoul JVM에서 실행한다. 같은 저장행의 legacy Hibernate/Jackson과 새 JDBC, 실제 nickname decoder, pool max1·실제 권한 실패/회복·읽기 전용을 검사한다.
4. 실제 JAR와 run 전용 S3 HTTP stub을 loopback에 띄워 기존 GET 회귀와 실제 발급기 JWT → multipart POST → GET을 확인한다. SDK가 전송한 bytes/MIME/순서, 인증·게시판 거부의 무부작용, Outbox/search 원자성, 권한·부분 업로드 실패 후 복구, 객체 유지·미삭제, 두 pool의 SIGTERM 종료·연결 해제를 검사한다. 필수 설정 누락도 listener·외부 접속 전에 거부하는지 확인한다.
5. F4는 같은 합성 DB의 최소 timestamp UPDATE·검색 DELETE 권한을 보완하고, 기존260건에 삭제 원자성·독립 연결의 행잠금·미디어 중 연결 반환·UTC/Seoul legacy auditing 비교를 더한다. 시험용 두 포트를 서로 다르게 선택하고 이번 자식의 bind 로그·readiness를 확인한다. 실제 bind 충돌이 확인된 경우만 최대3회 시도하고, 일반 기동 오류·timeout·강제 정리는 실패로 유지한다. 업무 fixture 초기화는 두 listener가 준비된 뒤에만 수행한다. F3 packaged 시험 뒤 opt-in DELETE를 허용한 같은 run 소유의 S3 stub으로 작성→삭제→조회404, 소유권·저장소 실패·DB rollback/회복·정상 종료를 확인한다.
6. F5는 같은 소유 fixture에서 부분 수정 권한만 추가하고 실제 legacy JPA의 null/dirty checking·응답/commit 시각을 UTC와 Seoul에서 비교한다. SDK stub을 재사용해 실제 JAR의 작성→수정→조회→삭제, 빈 파일·미디어 실패·DB rollback/회복·정상 종료를 확인한다. 새 모델·운영 데이터·외부 R2를 사용하지 않는다.
7. F6는 같은 fixture와 reader만 사용해 전체 목록·9필드·삭제/5종 게시판·동률 시각군·닉네임 실패/회복과 연결 반환을 검증한다. 실제 고정 Spring Data repository를 후보 SQL과 UTC/Seoul에서 비교한다. packaged 시험은 목록→단건·작성/수정 노출·삭제 제외를 실행하며 각 목록 GET 전후의 posts/Outbox/search와 S3 요청이 변하지 않는지 별도로 확인한다.
8. F7는 실제 고정 PostgreSQL 검색 service/분석기를 동일 fixture에서 실행해 UTC/Seoul 날짜·정렬Top10·전역 readiness·입력/권한/회복을 비교한다. 같은 reader의 최소 projection SELECT만 보완한다. packaged 시험은 작성→검색·수정→새 검색/이전 검색 제외·삭제→검색 제외를 확인하며 각 검색 GET의 DB/S3 무변경과 정상 종료를 별도로 검사한다.
9. F8는 고정된 실제 Comment entity·Spring Data repository·응답을 같은 합성 fixture에서 실행해 7필드·createdAt 정렬·동률군·댓글 삭제 제외·부모 soft delete/부재·닉네임 실패/회복과 reader 연결 반환을 UTC/Seoul에서 비교한다. legacy Controller·Service·Feign을 함께 실행하는 HTTP oracle은 아니다. F2→F3→F4→F5의 기존 owner/admin/reader/token marker 체인과 F7 최소 조회 권한을 확인하고, JDBC 주소가 label 소유 컨테이너의 실제 단일 loopback 공개 포트와 일치하는지 대조한 뒤 F8 marker·댓글 표·최소 SELECT만 준비한다. packaged 시험은 각 댓글 GET과 거부 경로 전후의 posts/comments/기존 F4 댓글·쪽지/Outbox/search 및 S3 요청을 비교하며, SQL 권한 실패/회복과 두 pool의 정상 종료·잔여 연결0을 검사한다. S3 stub은 기존 PUT/DELETE 기록과 함께 제어 경로를 제외한 GET/HEAD/POST의 성공·부재·거부 응답도 기록한다. 상태 조회 제어 호출은 기록하지 않아 snapshot 자체가 관측치를 바꾸지 않으며, 이 동작은 소켓을 열지 않는 별도 시험으로 확인한다.
10. always cleanup은 현재 run owner label의 합성 컨테이너만 정지/제거한다. 일반 prune·다른 컨테이너/볼륨 조작은 하지 않는다.

`scripts/f2_ci.py`·`f3_ci.py`·`f4_ci.py`·`f5_ci.py`·`f6_ci.py`·`f7_ci.py`·`f8_ci.py`·`f3_s3_stub.py`의 실행 가드는 이 저장소 GitHub-hosted Linux와 고유 run ID를 요구한다. DB mutation 전에는 loopback/정확한 시험 DB/marker/admin/reader를 대조한다. 미설정 테스트를 skip 성공으로 처리하지 않는다. `scripts/f2_fixture.sql`·`f3_fixture.sql`·`f4_fixture.sql`·`f5_fixture.sql`·`f7_fixture.sql`·`f8_fixture.sql`은 CI 전용이며 앱 resource/JAR에 포함되지 않는다.

`prepare_legacy_oracle.py`는 기준 `e8ad7b8ec9006772ccc5f5881334df2730dbd8de`의 정확히 6개 Java 파일만 해시 검증해 boot/build 안 test-only 경로로 추출한다. Post/BoardType/StringListConverter/PostResponse와 UserServiceClient 및 그 JDK-only 응답 의존 PrivateNoteModels다. legacy 전체 sourceDir·Application을 편입하지 않는다. 실제 source/버전 비교는 compatibility classpath에서만 이뤄지고 앱 JAR에는 들어가지 않는다.

`prepare_f3_legacy_oracle.py`는 F3 기준 `0e0161e03ddb31981487c4d0ece280cb04c1107c`의 기존 JWT 발급기·User 의존 타입·Outbox·Nori 등 정확히 9개 파일을 해시 검증해 test-only 경로로 추출한다. 실제 JPA/발급기/분석기와 후보의 차이를 UTC·Seoul 양쪽에서 비교한다. 생성한 합성 토큰은 run 전용 0600 임시 파일에만 두고 artifact에서 제외한 뒤 정리한다.

`prepare_f4_legacy_oracle.py`는 고정 F3 기준의 Post·BoardType·StringListConverter·OutboxEvent 해시와 이미 추출한 F2/F3 클래스를 대조한다. 같은 FQCN을 다시 생성하지 않는다. legacy JPA auditing 비교는 CI 소유 관리자 연결만 사용하고 후보 writer의 최소 권한과 구분한다.

`prepare_f6_legacy_oracle.py`는 고정 `a57ad47970a6df3fdad1d29d5c269f1f4ba8fff7`의 실제 PostRepository와 컴파일에 필요한 통계 응답2개를 해시 검증해 test-only 경로에 추출한다. 공유 엔티티·변환기·응답은 기존 F2 생성물과 바이트를 대조해 중복 클래스를 만들지 않는다. 동률 시각군은 행 구성과 필드를 비교하고 군 내부 배열 순서를 새 보장으로 고정하지 않는다. 새 fixture/권한은 만들지 않으며 기존 hosted owner/marker 체인을 재사용한다.

`prepare_f7_legacy_oracle.py`는 main `73f2009e63d61dd66b2df365452075d71a1757b3`의 실제 `PostgresqlSearchService`와 필요한 검색 계약/오류 타입을 고정 해시로 최소 추출한다. 공유 F2 entity/DTO/client와 F3 Nori/입력 예외는 고정 해시·기존 생성물 바이트를 대조하고 같은 FQCN을 중복 생성하지 않는다. 실제 검색 SQL을 시험에 복사한 결과를 legacy oracle이라고 부르지 않는다. 생성물은 compatibility 전용이고 실행 JAR에서 제외한다.

`prepare_f8_legacy_oracle.py`는 F8 기준 `6c76989c3da6dd3d8641ee90fdd38517ce068341`의 실제 `Comment`, `CommentRepository`, `CommentResponse` 세 파일만 고정 해시로 검증한 뒤 전용 test-only 경로에 추출한다. 전체 legacy application이나 HTTP 서비스는 기동하지 않는다. 다른 출력 경로·입력 변조·기존 생성물 덮어쓰기를 거부하는 Python 검사는 DB·컨테이너·JVM 없이 실행한다.

TestKit은 Gradle logging 충돌을 막기 위해 architectureTest 전용 classpath를 사용한다. 실제 업무 build와 guard를 fixture에 복사하고, 본 빌드가 해결한 BOM/의존 cache를 offline으로 사용한다. 허용/금지 fixture의 이유를 검사하며 일반 컴파일·다운로드 오류를 구조 차단 성공으로 취급하지 않는다.

Wrapper checksum 고정과 기존 최소 메모리·worker 1 설정을 유지한다. CI는 테스트 XML·source/runtime·oracle hash·packaged JAR hash/기동/종료를 7일 보존한다. Mac ARM64 운영 기동·전체 서비스 통합·RAM 절감의 증거와 구분한다.

전체 결정·계획·문제 해결 기록은 Obsidian `Projects/pawbridge`의 F2·F3·F4·F5·F6·F7·F8 계획과 모듈형 모놀리스 전환 노트를 따른다.
