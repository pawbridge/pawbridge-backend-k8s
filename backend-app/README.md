# PawBridge 업무 통합 실행기

Java 21·Gradle 8.14.3·Spring Boot 3.4.11의 독립 멀티프로젝트다. F2의 게시글 단건 GET·작성자 닉네임 조회, F3의 인증된 일반 게시글 작성, F4의 본인 게시글 삭제, F5의 본인 게시글 수정, F6의 공개 게시글 목록, F7의 공개 게시글 검색, F8의 공개 댓글 목록, F9의 인증된 댓글 작성, F10의 본인 댓글 삭제, F11의 본인 댓글 수정, F12의 인증된 본인 정보 조회, F13의 본인 닉네임 수정, F14의 본인 찜 여부 조회, F15의 공개 홈 영상 조회, F16의 공개 오늘 방문자 합계 조회, F17의 인증된 쪽지 수신자 확인 조회, F18의 표시 작성자 중 본인 차단 여부 조회, F19의 관리자 전체 회원 수 조회, F20의 본인 차단 목록 조회, F21의 본인 쪽지 알림 조회, F22의 본인 쪽지 단건 조회를 편입하는 후보이며 운영 Gateway를 이 실행기로 연결하거나 기존 서비스를 종료하지 않는다.

## F21 본인 쪽지 알림 조회

정확한 `GET /api/v1/notes/notifications?cursor=...`만 추가한다. 현재 회원 active → Clock 시각1회 → 받은함21행 → 표시20행의 null 제외·첫 등장 순서 sender 일괄 조회 → 다음 cursor → 전체 unreadCount 순서를 유지한다. 같은 요청 시각을 쓰지만 여러 조회의 원자 snapshot을 보장하지 않으며 단계별로 기존 RO tx/연결을 반환한다. User 단건·batch와 Clock·두 pool을 재사용한다.

정렬은 mailbox.created_at/UUID 내림차순이고 응답 createdAt은 note.created_at이다. 만료는 note.expires_at > now이며, 본인 INBOX 커서 anchor에는 만료 조건을 붙이지 않는다. 다른 회원·SENT·없는 cursor는 빈 content와 전체 unreadCount를 반환한다. 결과3필드·알림7필드와 nullable actorId·탈퇴 표시·원문 공백 nickname·Instant 미세초·read·href를 보존하고 본문은 읽거나 반환하지 않는다. 회원/저장소/일반503과 scoped no-store를 구분하며 발송·읽음·SSE·예약·원본 실행기 종료는 추가하지 않는다.

원본 annotated optional UUID의 실제 MVC12 관측을 먼저 수행해 기대값을 고정했다. missing/empty/space/empty-repeated는 null로200, 축약 UUID도 정규화되어200, 반복 값은 첫 값을 사용하고 CSV·잘못된 값·범위 초과 component는 서비스 호출 전400이다. 관측용 임시 클래스는 게시하지 않는다.

## 업무 경계와 호출

| 모듈 | 책임 | 직접 프로젝트 의존성 |
|---|---|---|
| boot | MVC·보안·DB·업무 구현 조립과 실행 JAR | user, community |
| user | 공개 닉네임/연락 회원 조회 계약·내부 본인 정보 조회·본인 닉네임 수정·찜 여부 조회와 JDBC·HTTP 구현 | 없음 |
| community | 게시글 단건·목록·검색·작성·수정·삭제·댓글 목록·작성·수정·삭제·미디어·검색 projection·공개 홈 영상·오늘 방문자 합계·쪽지 수신자·표시 작성자 차단 상태·차단 목록·쪽지 알림·단건 읽기 정책·REST 응답 | user |

```text
boot -> community.internal.post.web -> community.internal.post.application
                                      -> community.internal.post.infrastructure
                                      -> user.api -> user.internal.nickname.infrastructure
```

`api`는 업무 간 Java 계약이다. HTTP는 `internal.<기능>.web`, DB/JSON 변환은 infrastructure, 조회 조합은 application이 담당한다. 읽기 projection에 빈 domain 계층이나 회원 전체 aggregate를 만들지 않는다. F3의 domain은 일반 게시판 작성 가능 규칙과 저장 입력만 담당한다.

Community는 User의 공개 API만 참조한다. User 부재·명시된 조회 실패는 `사용자{id}`로 표시하고, 빈 HTTP 응답이 null로 decode되던 동작은 빈 저장 닉네임의 표시 호환에 반영한다. 공백은 그대로 둔다. 게시글 없음/soft-delete는 404이며 게시글 DB·JSON 오류는 닉네임 fallback로 숨기지 않는다.

공개 게시글 GET은 `/api/v1/posts/read/{postId}` 단건, `/api/v1/posts/read` 목록, `/api/v1/posts/search?keyword=...` 검색이다. 기존 envelope와 data 9필드, 이미지 null/순서/중복과 LocalDateTime 의미를 보존한다. 공개 댓글 GET `/api/v1/comments/posts/read/{postId}`는 아래 F8의 7필드 배열 계약을 따른다. 기존 malformed ID의 generic 500 동작은 유지하며 400 개선을 섞지 않는다. `/api/posts/...` alias rewrite는 기존 Gateway 책임이다. 작성자 닉네임 조회는 업무 간 Java 계약으로 연결한다. 본인 닉네임 수정 HTTP는 아래 F13의 정확한 PUT 경로만 제공하며 로그인·신규 토큰 발급 API는 없다. F3는 같은 기존 경로 `POST /api/v1/posts`의 multipart 작성만 추가한다.

F17은 `GET /api/v1/notes/recipients/{member}`만 추가한다. 서명 JWT의 Long principal로 요청자를 확인한 뒤 자기 자신→양방향 차단→상대 active 순서로 검사하고, User 소유의 `ContactMemberQuery.get(long)`가 제공하는 원문 닉네임·active/deletionPending을 사용한다. 신규 reader 권한은 `contact_deletions.user_id`와 `private_note_blocks.member_id/blocked_id` 세 열뿐이며 본문/메일함/연락처 PII·member/시각 열·writer 권한을 추가하지 않는다. User 조회→차단 조회→상대 조회마다 기존 짧은 RO tx/연결을 반환하며 외곽 TX·Feign·새 pool·event·SSE·탈퇴/차단 쓰기·예약을 편입하지 않는다. 성공은 정확한 4필드, 서비스/바인딩 오류는 message-only와 no-store이며 User 조회 불가503과 차단 저장소503을 구분한다. 인증 오류의 기존 envelope는 유지한다. 이는 후보 코드이며 운영 라우팅이나 원본 서비스 종료를 뜻하지 않는다.

F18은 `GET /api/v1/notes/blocks/status?memberIds=...`만 추가한다. 원래 입력 목록의 1~100개 양수 검증→현재 회원 active→중복 제거→본인이 차단한 outgoing ID 오름차순 조회를 보존한다. F17 User API·세 표·기존 reader 권한을 재사용하며 target User 조회·새 DDL/ACL·writer·page·추가 필드·외곽 TX를 만들지 않는다. 성공 data는 `memberIds` 한 필드이고 reverse-only/미일치는 제외한다. 누락400은 공통 envelope, 변환/서비스 오류는 message-only이며 no-store와 쿠키 없음은 해당 GET에 한정한다. 빈 query 값의 service400과 CSV+반복 혼합의 변환400은 실제 원본 MVC 관측에 맞춘다. F17 수신자 조회와 미편입 notes/chats·쓰기 경로 기본 거부를 유지하며 운영 라우팅은 변경하지 않는다.

F17과 F18의 새 RO TX에서 연결 생성·시스템 완료·deadline 실패는 구체적인 CannotCreateTransactionException/TransactionSystemException/TransactionTimedOutException만 저장소503으로 분류한다. User typed 실패는 회원503, transaction usage와 그 외 Runtime은 일반 쪽지503을 유지한다. 원본 autocommit의 DAO 연결 실패가 후보의 TX 예외로 감싸지는 기술적 차이에 대한 명시적 적응이다.

## DB·실행 설정

읽기용 DataSource·transaction manager와 쓰기용 DataSource·transaction manager를 명시적으로 구분한다. 읽기는 `pawbridge_community.posts`, `pawbridge_community.comments`, `pawbridge_community.post_search_documents`, `pawbridge_user.users`, `pawbridge_user.favorites`, `pawbridge_community.home_videos`, `pawbridge_community.daily_visit_count`, `pawbridge_user.contact_deletions`, `pawbridge_community.private_note_blocks`의 필요한 열만 읽는다. Community 읽기 조합은 게시글·댓글 tx를 종료하고 연결을 반환한 뒤 User 닉네임 조회 tx를 시작한다. 이 읽기 조합에는 application 외곽 tx나 REQUIRES_NEW를 두지 않는다. F13 닉네임 수정은 별도로 기존 writer의 application 트랜잭션 하나를 사용한다. SQL의 schema와 parameter binding을 명시한다.

| 환경 변수 | 의미 |
|---|---|
| F2_JDBC_URL | 명시적 PostgreSQL JDBC URL. 계정/쿼리 옵션을 URL에 넣지 않음 |
| F2_DB_USER | 대상 두 schema의 필요한 열을 읽을 계정 |
| F2_DB_PASSWORD | 해당 계정 암호. 이미지/소스에 포함하지 않음 |
| F2_POOL_MAX_SIZE | 후보 시험 pool 상한, 기본 2; 운영 pool 확정값 아님 |
| F2_PORT | loopback HTTP 포트. 기본 0(임의 포트) |

필수 설정이 없으면 main은 Spring 기동/DB 접속 전에 실패한다. 운영 기본 주소나 기존 .env를 자동 탐색하지 않는다. 조회에는 readOnly tx와 SQL READ ONLY를 적용하며 CI reader 계정도 SELECT-only로 제한한다. 앱 classpath에 SQL 초기화·Flyway·기존 migration/consumer/스케줄러·Feign·JPA·Redis·Kafka·ES·OAuth를 추가하지 않는다. F3의 S3 SDK는 명시적 endpoint·region·정적 credential만 사용한다. 기본 AWS credential/region 탐색 체인이나 기존 Secret 파일을 사용하지 않는다.

DB의 `TIMESTAMP(6)`와 기존 Hibernate UTC JDBC 추출 의미를 보존하도록 mapper를 비교한다. 문자열에 Z를 붙이거나 무조건 9시간을 더하지 않는다. 이 후보를 운영으로 연결하기 전에는 운영 DB 권한·기존/신규 JVM timezone·총 pool 예산과 라우팅 인계를 별도 검증해야 한다.

## F3 작성 계약과 별도 쓰기 연결

`POST /api/v1/posts`는 기존 `title`, `content`, `boardType`, 선택 `files` multipart 계약과 HTTP 200 envelope·응답 9필드를 유지한다. 검증된 Bearer JWT의 양수 `userId`만 사용하며 클라이언트의 작성자 헤더·파라미터를 신뢰하지 않는다. 기존 발급기의 access token·역할을 수용하고 refresh·위조·만료·잘못된 identity는 업로드·DB 작업 전에 401로 거부한다. 신규 로그인·토큰 발급·갱신은 구현하지 않는다.

일반 게시판 3종만 작성하고 MISSING/REPORT는 업로드 전에 전용 등록 안내로 거부한다. 기존에 적용되지 않던 NotBlank 등 새 입력 규칙은 추가하지 않는다. 작성 순서는 게시판 검증 → S3 업로드 → 독립된 짧은 User 닉네임 조회 → writer 트랜잭션 → 응답이다. 예상 밖 닉네임 오류가 DB commit 뒤에 발생하지 않도록 기존 HTTP 호출 순서를 조정했다. User 부재·명시적 unavailable fallback은 F2와 같으며, 닉네임과 게시글의 동일 시점 snapshot을 보장하지 않는다.

writer는 같은 명시적 JDBC URL에 별도 계정·pool max1/minIdle0으로 연결한다. reader 기본 max2와 합쳐 후보 기본 상한은 3이며 운영 pool 예산을 확정한 수치는 아니다. 두 연결·JDBC·TM은 qualifier로 조립한다. F3 게시글 작성 조합에는 application 외곽 트랜잭션·REQUIRES_NEW·XA를 추가하지 않는다. `posts`, `outbox_events`, `post_search_documents`의 3개 쓰기는 writer의 단일 트랜잭션에 참여한다. 기존 POST_CREATED JSON·Nori 9.12.3·검색 가중치·UTC JDBC 바인딩 의미를 보존한다. F3 범위에는 검색 조회나 이벤트 소비자를 추가하지 않으며 검색 조회는 아래 F7 계약으로 구분한다.

| 추가 필수 환경 변수 | 의미 |
|---|---|
| F3_DB_WRITER_USER / F3_DB_WRITER_PASSWORD | reader와 다른 최소 쓰기 계정. F3~F12는 User 쓰기를 거부하며 F13은 nickname/updated_at 두 열 UPDATE만 허용. DDL·소유권 권한 없음 |
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

실행기에는 기존 두 pool과 writer/JWT/S3 필수 설정이 유지된다. 합성 CI의 F8 단계에서만 소유권 marker를 확인한 기존 PostgreSQL에 실제 baseline과 같은 댓글 표와 reader의 7개 SELECT 열을 추가한다. F4의 별도 FK 댓글·쪽지 fixture는 보존하며 F8은 댓글 쓰기 권한을 추가하지 않는다. F9의 합성 writer 보완은 아래 별도 계약이며 운영 스키마·권한을 변경하지 않는다. 전체 댓글·행별 닉네임의 운영 데이터량 성능과 Gateway 전환은 별도 검증 대상이다.

## F9 인증된 댓글 작성

`POST /api/v1/comments/posts/{postId}`는 JSON `content`를 받아 검증된 기존 Bearer JWT의 양수 `userId`로 댓글 한 행을 생성한다. USER·ADMIN·SHELTER를 수용하며 위조 작성자 헤더를 신뢰하지 않는다. 성공은 HTTP/code200·`댓글이 생성되었습니다.`·F8과 같은 정확히 7필드다. identity ID를 사용하며201·연속 ID·중복 방지를 추가하지 않는다. F9 단계의 쓰기는 작성 한 계약이며 삭제는 아래 F10 계약으로 추가한다. F8에서 거부하던 이 POST의 익명 요청은 인증 필요401로 바뀌고 공개 GET은 유지한다.

실제 기존 요청 DTO의 `@NotBlank`만 적용한다. 누락/null/빈 문자열/기존 ASCII blank는400·`content: 내용은 필수입니다`이며 잘못된 JSON·누락 본문은400·`요청 본문 형식이 올바르지 않습니다.`다. 검증 provider의 기존 trim 기반 의미를 유지하여 U+2003·전각 공백·앞뒤 공백·줄바꿈을 임의로 제거하거나 새 길이 제한을 두지 않는다. malformed/long 초과 경로는 기존 안전한500이며0·음수는 부모 조회 결과를 따른다. 버전 관리된 V3 `ck_rollback_charset_comments` 위반만400·`일부 이모지 등 지원하지 않는 문자를 제외해 주세요.`로 구분하고, NUL 등 다른 SQL 실패는 안전한500이다.

활성 부모를 기존 `PostReader`로 확인하고 연결을 반환 → 작성자 `UserNicknameQuery` 연결을 반환 → 기존 writer의 짧은 트랜잭션으로 comments INSERT/RETURNING/commit → 응답 순서다. 부모 부재·삭제는 User 조회보다 먼저404·`게시글을 찾을 수 없습니다.`이며 모든 게시판·타인 글에 댓글을 허용한다. 새 회원 상태·부모 소유자·게시판 정책은 없다. 단일 부모 확인 뒤 삭제가 경쟁하면 작성될 수 있는 기존 경계를 유지하며 새 FK·잠금·재조회는 넣지 않는다.

작성자 부재·명시적 조회 불가는 `사용자{id}`, 빈 닉네임은null, 공백은 원문을 유지한다. 예상 밖 User 오류는 쓰기 전500으로 닫는다. legacy의 INSERT 뒤 Feign 호출·넓은 `catch(Exception)` 순서와 모든 장애를 동등하게 재현했다고 주장하지 않는다. 확정 SQL/commit rollback은 댓글 행을 남기지 않으며 sequence gap은 허용한다. commit 결과 불명·응답 전송 실패를 확정 rollback으로 표현하거나 서버가 POST를 자동 재시도하지 않는다. 즉시 응답 시각의 정밀도와 DB `TIMESTAMP(6)`를 재조회한 정밀도를 억지로 같게 만들지 않는다.

Outbox·검색 projection·S3는 호출하지 않는다. 기존 두 pool·JDBC URL·writer/JWT/S3 필수 설정을 재사용하며 새 운영 환경 변수는 없다. 기존 JSON Bean Validation에 필요한 `starter-validation`/validation API만 추가하고 버전은 BOM을 따른다. 실행 JAR 검사는 정상 `BOOT-INF/lib/hibernate-validator-<version>.jar`만 좁게 허용하며 Hibernate ORM·JPA·레거시·테스트 artifact 차단을 유지한다.

## F10 본인 댓글 삭제

정확한 `DELETE /api/v1/comments/{commentId}`만 기존 JWT 인증 대상에 추가한다. 검증된 principal만 사용하고 위조 작성자 헤더를 무시한다. USER·ADMIN·SHELTER의 본인 댓글은 허용하며 타인 ADMIN도403·`댓글에 접근할 권한이 없습니다.`다. 미삭제 댓글을 먼저 찾고 부재·이미 삭제는404·`댓글을 찾을 수 없습니다.`다. 익명은 경로 바인딩보다 먼저401이며 malformed/overflow ID는 기존 안전한500,0·음수는 조회 결과를 따른다. 성공은 HTTP/code200·`댓글이 삭제되었습니다.`·`data:null`이다. DELETE 본문은 읽거나 검증하지 않는다. 댓글 PUT은 아래 F11 계약으로 추가하며 인접 경로 거부를 유지한다.

같은 writer 트랜잭션에서 활성 댓글 작성자를 일반 SELECT → 소유권 확인 → 두 시각 생성 → ID 조건의 UPDATE → commit 순서로 처리한다. 물리 행·ID·부모 ID·작성자·본문·생성 시각은 보존하고 `deleted_at`, `updated_at`만 갱신한다. 삭제 시각과 auditing 시각을 새 동일값 불변식으로 만들지 않는다. 부모 존재/삭제·게시판·User 상태와 무관하며 부모·User·S3·Outbox·검색을 호출하지 않는다. 기존 writer pool max1과 두 pool/JDBC/TM·운영 설정을 그대로 사용한다.

레거시 댓글에는 버전·명시적 잠금이 없으므로 F4 게시글의 단일 승자 정책을 옮기지 않는다. 두 삭제가 활성 상태를 먼저 읽으면 둘 다200일 수 있고 순차 재삭제만404다. `FOR UPDATE`·active/author UPDATE 조건·재조회·자동 재시도를 추가하지 않는다. 조회 뒤 물리 행이 사라져 UPDATE0이면 기존 stale-state와 같은 안전한500이다. 후보의 두 열 갱신은 레거시의 넓은 Hibernate flush와 달리 동시 PUT의 본문을 덮지 않는 의도된 차이다. 모든 수정/삭제 경쟁이 동일하거나 역방향 legacy PUT의 삭제 재활성화까지 해결됐다고 표현하지 않는다.

구조화된 community rollback charset SQLState23514만 기존 메시지400으로 보존하고 다른 CHECK·SQL·commit 오류는 안전한500이다. 이는 DELETE 본문 문자 검증을 추가하는 것이 아니다. 확정 rollback의 행 보존을 확인하며 commit 결과 불명·응답 전송 실패를 확정 rollback으로 표현하지 않는다.

CI는 F3~F9의 원래 ACL 검증을 모두 끝낸 뒤 F10 권한을 보완한다. F10 준비 전 `f9.require_fixture()`의 strict 검사를 유지하고, 준비 후에는 F8의 owner/정확한 loopback port 체인과 F9/F10 owner·marker, 기존 문자 CHECK/7열 및 정확한 확장 ACL을 확인한다. 기존 writer에 `SELECT(author_id,deleted_at)`, `UPDATE(deleted_at,updated_at)`만 추가하며 물리 DELETE·본문 UPDATE·sequence·User 쓰기·schema CREATE 권한은 없다. F10 단계에서 F9 strict helper를 재호출하지 않고 작성은 실제 F9 HTTP API로 확인한다.

같은 compatibility sourceSet에서 기존 UTC/Seoul 두 task는 F10·F11·F12 클래스를 제외하고 `commentDeleteCompatibilityTest`·`commentDeleteCompatibilitySeoulTest`는 F10의 정확한 FQN만 선택한다. 새 sourceSet·계정·pool은 없다. 두 전용 XML에 실제 실행 건수가 있고 실패·오류·skip이 없는지 별도로 확인한다. 경합 시험의 독립 연결2개는 시험 도구이며 production writer pool을 늘리지 않는다. 기존 F8 packaged 거부 검사에서도 새 DELETE 익명 응답만401로 갱신한다.

## F11 본인 댓글 수정

정확한 `PUT /api/v1/comments/{commentId}`는 JSON `content`와 기존 JWT principal을 사용한다. USER·ADMIN·SHELTER의 본인만 허용하며 위조 작성자 헤더를 무시한다. 익명/잘못된 JWT는 경로·본문 바인딩보다 먼저401이다. 실제 기존 요청 DTO의 `@NotBlank`를 유지하여 누락/null/빈 문자열/ASCII blank는400·`content: 내용은 필수입니다`, 잘못되거나 누락된 JSON 본문은400·`요청 본문 형식이 올바르지 않습니다.`다. U+2003·전각 공백·원문·무상한을 보존한다. malformed/overflow ID는 안전한500,0·음수·부재·이미 삭제는404다. 본문 검증은 유스케이스의404/403보다 먼저 실행될 수 있다.

활성 댓글 snapshot 조회와 소유권 확인 → reader 연결 반환 → User 닉네임과 연결 반환 → 최초 content와 요청 원문 비교 → 다를 때만 짧은 writer 트랜잭션 → commit → 응답 순서다. 부재는404·`댓글을 찾을 수 없습니다.`, 타인은 ADMIN도403·`댓글에 접근할 권한이 없습니다.`이며 User·쓰기보다 먼저다. 부모·회원 상태를 새로 확인하지 않는다. 같은 본문도 닉네임은 조회하고 missing/unavailable은 `사용자{id}`, 빈 닉네임은null, 공백은 원문을 유지한다. 예상 밖 User 오류는 쓰기 전500이다. 동일 본문은 modification 호출·쓰기 트랜잭션 자체가 없다.

성공은 HTTP/code200·`댓글이 수정되었습니다.`·기존7필드다. content는 요청 원문이며 createdAt·updatedAt은 최초 저장 snapshot 값이다. 변경 시 DB updated_at은 commit auditing 시각으로 바뀌므로 이후 F8 GET은 새 시각을 보여준다. HTTP 성공 전 commit을 완료한다. 후보 SQL은 `content`, `updated_at` 두 열만 ID 조건으로 갱신해 deleted_at과 다른 저장값을 보존한다. UPDATE0은 안전한500이다. `FOR UPDATE`·active/author/content UPDATE 조건·재조회·version·retry는 없다. 최초 snapshot과 같은 요청은 그 뒤 다른 요청이 바꾼 DB content를 덮지 않고 이전 snapshot을 응답할 수 있다. 변경으로 판단했다면 현재 DB가 이미 요청 content여도 auditing을 수행한다. 먼저 commit된 삭제 상태를 보존하는 두 열 갱신은 legacy 전체열 flush의 재활성화를 피하는 명시된 차이이며 모든 경합이 동등하다고 주장하지 않는다.

구조화된 community rollback charset23514만 기존 고정400이며 NUL·다른 SQL/CHECK/commit은 안전한500이다. 자동 재시도·Outbox·검색·S3 호출은 추가하지 않는다. 기존 두 pool과 JDBC/TM·writer·필수 운영 설정을 재사용하며 새 runtime 환경 변수는 없다.

CI는 기존 F9·F10의 strict ACL과 PG/XML/JAR 회귀를 끝낸 뒤 F11을 준비한다. `f10.require_fixture()`로 변경 전 상태를 확인하고 F11 owner/marker와 기존 writer의 `UPDATE(content)` 한 열만 추가한다. 확장 뒤에는 F8의 실제 단일 loopback port·owner 체인과 F9/F10/F11 owner·marker, 7열·문자 CHECK·정확한 ACL을 직접 확인한다. INSERT5·SELECT(id,author,deleted_at)는 유지하고 UPDATE는 content/deleted_at/updated_at만 허용하며 sequence·물리 DELETE·User 쓰기·schema CREATE는 금지한다. F9/F10 strict helper를 재호출하지 않는다. 수정 전용 `commentUpdateCompatibilityTest`·`commentUpdateCompatibilitySeoulTest`는 정확한 F11 클래스만 실행하고 두 XML의 실행 비0·실패/오류/skip0을 검사한다. 기존 F8 익명 PUT 한 입력은401로 바꾸고 인접 경로 거부는 유지한다.

packaged JVM은 Asia/Seoul로 실행한다. 후속 GET 시각은 실제 reader의 UTC Calendar 조회와 `Timestamp.toLocalDateTime()` 의미에 맞춰 raw 저장 TIMESTAMP를 UTC에서 Seoul로 변환한 값과 대조한다. 이전 GET과 PUT 응답의 oldupdatedAt 비교는 그대로 유지한다. 날짜 경계를 넘는 변환과 stale·잘못된 새 시각 거부는 DB 없는 Python 회귀로 보호한다.

## F12 인증된 본인 정보 조회

정확한 `GET /api/v1/users/me`만 기존 Bearer 검증 대상에 추가한다. 서명·만료·양수 userId·비null String role 검증을 재사용하고 검증된 principal의 ID만 조회한다. X-User-*·query·본문의 다른 회원 ID를 무시한다. 기존 JWT subject는 email이며 식별자는 userId다. 새 subject 일치·issuer/audience·role allowlist·회원 상태 정책은 없다. 기존 Gateway가 subject/name을 헤더용으로 읽는 것과 후보 검증기의 차이는 유지한다. USER·ADMIN·SHELTER 모두 본인 조회를 허용한다. 기존 공개 GET·인접 경로 거부와 내부 ERROR dispatch 의미는 유지한다.

성공은 HTTP/code200·`요청이 성공적으로 처리되었습니다.`·정확7필드 `userId,email,name,nickname,provider,role,createdAt`다. 전부 현재 DB 값이며 JWT의 email/name/role을 복사하지 않는다. 빈 nickname과 공백은 그대로 반환하고, nullable TIMESTAMP(6)의 createdAt은 명시적 JSON null을 유지한다. 댓글의 nickname fallback/empty→null이나 임의 현재 시각을 가져오지 않는다. 실제 User에는 deleted_at/status가 없으며 pending 탈퇴·로그아웃 blacklist 검사를 추가하지 않는다. 실제 행 부재는 기존400·`존재하지 않는 회원입니다.`·data:null이며 DB/연결/트랜잭션·예상 밖 오류는 안전한500이다. 인증 실패401은 조회보다 먼저 반환하며 SQL 실패를 회원 부재로 숨기지 않는다.

User 내부 `UserProfileQuery.require(id)`가 같은 reader의 3초 read-only 트랜잭션 안에서7열을 schema-qualified 조회하고 부재 application 예외를 발생시킨다. 단순 위임 usecase·미사용 공개 profile API·빈 domain이나 전체 User aggregate는 없다. 기존 UserNicknameQuery와 Community production은 유지하며, 같은 두 pool/JDBC/TM·writer/JWT/S3 필수 설정을 재사용한다. 새 runtime 환경 변수·쓰기 권한·Outbox·검색·S3 호출은 없다. 실제 NOT NULL·role CHECK가 보장하는 저장 문자열을 신뢰하며 제약을 제거한 손상 데이터의 legacy enum 처리까지 동등하다고 주장하지 않는다.

CI는 원래 두 열 User fixture의 F3~F11 PG/XML/JAR 회귀를 모두 끝낸 뒤 F12를 준비한다. DDL 전에 `f11.require_fixture()`로 단일 loopback port·owner/marker·기존 comment writer ACL을 확인한다. 새9열 중 email/name/provider/role은 기존 합성 ID별 값으로 채운 뒤 NOT NULL과 검증된3역할 CHECK를 적용하며, created_at·password/care_reg_no/provider_id/updated_at은 실제 nullable 의미를 유지한다. 확장 전후 기존 ID/nickname 배열이 같아야 추가 SELECT를 부여한다. reader에는 기존2열에 email/name/provider/role/created_at만 더해 정확7열 SELECT를 허용하고 민감4열 SELECT·양 계정 User 쓰기·schema CREATE는 금지한다. F12 marker와11열 타입/길이/nullability·CHECK·ACL을 검사하고 ancestor 초기화 smoke를 재실행하지 않는다.

`profileCompatibilityTest`·`profileCompatibilitySeoulTest`는 같은 sourceSet에서 F12 클래스만 실행하고 두 XML의 실제 비0·실패/오류/skip0을 검사한다. 실제 고정 User/Role·SimpleJpaRepository.findById·DTO/Jackson을 UTC/Seoul에서 비교하며 전체 UserService·Controller·Gateway 실행 oracle은 아니다. JAR는 현재 단계에서 기존 실제 발급기로 갱신한 합성 토큰과 별도 본인 ID를 사용해 역할·위조 ID·현재 DB·빈/공백/null·회원 부재·권한 실패/회복·요청 간 principal 정리를 확인한다. raw 날짜는 기존 F11의 순수 UTC→Seoul 비교를 재사용한다. 요청마다 User/댓글/posts/F4 댓글·쪽지/Outbox/search/S3 전체 불변, 민감값 비노출·두 pool 정상 종료·연결0을 확인한다.

## F13 본인 닉네임 수정

`PUT /api/v1/users/me/nickname`은 기존 검증된 JWT principal의 본인만 대상으로 JSON nickname을 처리한다. 실제 요청 DTO의 NotBlank·길이2~30·한글/영문을 최소 하나 포함하는 한글/영문/숫자 패턴을 유지하고 trim·대소문자 정규화·새 회원 정책은 추가하지 않는다. `ChangeUserNickname`의 실제 Spring proxy가 기존 `writeTransactionManager` 한 트랜잭션 안에서 현재 nickname 조회→원문 동일값이면 중복 조회·UPDATE·auditing 생략→DB lower 중복 조회→nickname/updated_at 두 열 갱신을 수행한다. 성공은 동일값도 HTTP/code200·`닉네임이 변경되었습니다.`·data:null이다. 회원 부재400·사전 중복409를 유지한다. 원본의 managed save 이후 UNIQUE 경합23505는 메서드 catch 밖 commit에서 실패하며, 후보도 JDBC UPDATE에서 발생한 실제 DB 경합을 일괄409로 바꾸지 않고 안전한500으로 표현한다. 구조화된 User schema·23514·rollback charset 제약 metadata만 고정400으로 표현한다. 후보의 좁은 UPDATE는 원본 전체행 flush와 달리 동시 변경된 role/비밀번호를 덮지 않는다. 새 pool·환경 설정·잠금·version·retry·Outbox·S3 호출은 없다. 로컬 시험과 JAR 빌드는 운영 배포나 실제 운영 DB 권한 변경의 근거가 아니며, 이 후보는 미배포 상태다.

## F14 본인 찜 여부 조회

`GET /api/v1/favorites/{animalId}/check`는 검증된 JWT principal의 userId와 경로의 원문 Long animalId로 존재 여부만 조회한다. User/Animal 부재 확인이나 양수 검증·외부 호출을 추가하지 않는다. 성공은 HTTP/code200·기본 성공 문구·boolean data이며 찜이 없거나 회원이 없어도 false다. X-User-*·query·GET body로 식별자를 바꾸지 않는다. 기존 reader의 3초 read-only 트랜잭션에서 두 열만 조건으로 사용하며 SQL/연결/트랜잭션 오류를 false로 숨기지 않고 안전한500으로 반환한다. 원본 Controller/Advice의 실제 변환 오류도500을 유지하며 새400 개선을 섞지 않는다. 새 pool/TM·환경 변수·모듈 공개 API·Outbox·S3 호출은 없다. 찜 POST/DELETE와 운영 DB 권한·배포는 이 후보 범위가 아니다.

## F15 공개 홈 영상 조회

정확한 `GET /api/v1/home/videos`는 익명·잘못된 Bearer도 허용하는 공개 조회다. `ReadHomeVideos`가 Clock.instant를 한 번 읽어 정확한 Duration 29일 전 cutoff를 DB 연결 전에 계산한다. reader는 published·available·checked_at > cutoff를 만족하는 영상을 position/created_at/id 오름차순으로 최대3개 읽고, 결과가 없으면 정상 빈 배열을 반환한다. UUID·영상ID·게시/위치·Instant 두 필드·nullable metadata 네 필드·available의 정확11필드와 null/빈 문자열을 보존한다. TSTZ를 Instant로 읽으므로 기존 TIMESTAMP의 Seoul9시간 비교 helper를 사용하지 않는다. DB·연결·TX 시작/완료·예상 밖 Runtime은 원본의 안전한503 메시지로 표현하고 예외 타입만 기록한다.

같은 reader·3초 read-only TX와 두 pool을 재사용한다. 원본 home()는 명시적 TX 없이 조회하므로 보호 TX 도입은 후보의 기술적 차이다. 독립 Clock.systemUTC만 조립하며 원본 PrivateNoteConfiguration·HomeVideoRefresh·YouTube client·API key·예약/metadata 갱신을 등록하지 않는다. board 권한·새 writer 권한·User/Outbox/search/S3 호출이나 JWT 발급/갱신은 없다. 관리자 편집과 구 실행기의 예약 소유권 이전·운영 배포는 이 후보에 포함되지 않는다.

## F16 공개 오늘 방문자 합계 조회

정확한 `GET /api/v1/visits/today`는 기존 `pawbridge.visits.enabled` 설정과 `DAILY_VISITORS_ENABLED` 환경 변수의 기본 false를 유지한다. 단독 조회 모드(`pawbridge.visits.collection-enabled=false`)에서 true일 때 조회 실행자·reader·controller·advice만 조립하며 서명키·VisitCookie·원본 lifecycle·시작 INSERT·집계 POST·정리/예약 작업은 등록하지 않는다. 공개 GET 보안 matcher는 두 상태에서 같고 false/누락은404다. 이404 본문을 원본 global envelope와 동일하다고 주장하지 않으며 미편입 경로를 열지 않는다.

조회 실행자는 F15의 Clock에서 instant를 한 번 읽고 서울 날짜를 TX/연결 전에 결정한다. DATE 조건·BIGINT 합계만 읽어 정확한 date/count 두 필드와 부재0을 반환한다. Date.valueOf 바인딩, 원문 long JSON number와 최초 날짜를 보존하며 대기 중 자정 재검사·현재 시각 재조회·음수/null 보정은 없다. 익명·잘못된 Bearer·X-User 헤더·Origin·Cookie·bot 여부로 합계를 바꾸지 않는다. 성공과 DataAccessException503은 명시적 no-store이며 쿠키를 발급하지 않는다. DAO503은 `방문 통계를 불러올 수 없습니다.`, 일반 Runtime500은 `서버 에러!`다. 실제 commit 예외가 DAO로 번역되면503이며 TX 실패를 모두500/503으로 묶지 않는다. 일반500에는 별도 캐시 정책을 추가하지 않고 공통 보안 헤더를 구분한다.

기존 reader의 3초 read-only TX는 원본 today()의 autocommit과 구분되는 후보 보호다. 새 pool/TM·JWT 정책/발급·서명키·외부 요청·쓰기·Outbox·이벤트는 없다. 시작 기록·집계·cleanup은 기존 실행기가 계속 소유하고 조회 편입을 운영 배포나 기존 실행기 종료로 표현하지 않는다.

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
10. F9는 F8의 owner·정확한 컨테이너 포트·marker 체인을 확인한 같은 fixture에 F9 marker·V3 comments 문자 CHECK·기존 writer의 5열 INSERT/ID SELECT만 보완한다. PostgreSQL identity에는 별도 sequence 권한이 필요 없으므로 sequence USAGE/SELECT/UPDATE가 모두 없는지 검사한다. UPDATE/DELETE·User 쓰기·schema CREATE도 주지 않는다. 고정된 실제 요청 DTO validator·공유 Comment entity/repository/DTO·JPA auditing과 후보를 UTC/Seoul에서 비교한다. packaged 시험은 기존 발급기 인증·모든 게시판·타인 글·JSON/blank/Unicode·부모404·SQL 실패·INSERT/RETURNING 권한 회복·닉네임 fallback/회복과 POST→F8 GET을 확인한다. 성공 POST는 댓글 한 행만 추가하고 기존 댓글/User/posts/F4 댓글·쪽지/Outbox/search/S3를 유지하며, 실패 요청과 GET은 DB/S3 전체 무변경을 확인한다. 즉시/저장 시각의 정밀도 차이·두 pool 종료·잔여 연결0·로그 크기/비밀값 비노출을 별도로 검사한다.
11. 기존 F3~F9 PG/packaged 회귀와 정상 종료를 원래 ACL에서 끝낸 뒤 F10 가드→권한 준비→삭제 전용 UTC/Seoul→packaged 순서로 진행한다. 기존 고정 Comment entity/repository/DTO·실제 auditing을 재사용해 두 시각·물리 보존·부모/User 조회 불필요·SQL/commit rollback·경쟁과 최소 갱신 차이를 비교한다. 전체 legacy Controller/Service E2E는 아니다. packaged 시험은 실제 F9 POST→F8 GET→본인 DELETE→GET 제외, 역할·타인/부재/재삭제·본문 무시·부모/User 부재·SELECT/UPDATE 실패와 회복을 확인한다. 모든 요청에서 댓글 외 User/posts/F4 댓글·쪽지/Outbox/search/S3 불변을 검사하고 삭제는 기존 행과 두 시각 외 열을 보존한다. 실패 요청·GET은 전체 DB/S3 무변경이며 두 pool 정상 종료·잔여 연결0·로그 크기/비밀값 비노출을 확인한다.
12. F10 회귀를 끝낸 뒤 F11 가드→content 한 열 권한 준비→수정 전용 UTC/Seoul/XML→packaged 순서로 진행한다. 실제 고정 요청 validator·managed JPA dirty checking·commit auditing과 후보의 oldupdatedAt 응답·저장 시각·동일 본문 no-op·경합 차이를 비교한다. packaged 시험은 실제 POST→저장된 GET snapshot→changed/same PUT→GET→DELETE와 인증·소유권·JSON/blank/Unicode·부재/삭제·부모 무관·닉네임 fallback/회복을 확인한다. UPDATE(content,updated_at) 권한을 회수한 상태에서 동일 본문은 성공하고 변경은500이어야 하며 복원 후 회복을 확인한다. 성공 변경은 content/updated_at만 바꾸고 다른 댓글/User/posts/F4 댓글·쪽지/Outbox/search/S3는 유지한다. 동일 본문·실패·GET은 전체 DB/S3 무변경, 두 pool 정상 종료·잔여 연결0·로그 크기/비밀값 비노출을 확인한다.
13. 원래 F3~F11 회귀 뒤 F12 가드→User fixture 준비·ID/nickname 보존→profile 전용 UTC/Seoul/XML→packaged를 실행한다. 고정된 실제 User JPA/DTO와 가입일 null·날짜 경계·마이크로초·역할/닉네임을 비교한다. JAR는 본인 정보 exact7·DB/JWT 값 차이·두 principal·200/400/401/500·인접 경로403과 SQL 권한 회복을 확인하고 각 요청의 전체 DB/S3 불변·두 pool 종료/접속0·민감 필드 응답/로그 제외를 검사한다.
14. F3~F12 회귀를 각 단계 원래 권한에서 끝낸 뒤 F13 가드→기존 User ID/nickname 보존·고정 V1 UNIQUE/lower index·V2 8열 문자 CHECK·writer SELECT(user_id,nickname)/UPDATE(nickname,updated_at) 준비→nickname 전용 UTC/Seoul/XML→packaged를 실행한다. 확장 뒤 F12 무권한 helper를 호출하지 않고 F11 owner/정확한 포트/댓글 ACL 체인과 F12 schema/reader7, F13 writer exact ACL을 대조한다. JAR 직전 고정 원본 발급기로 600초 합성 토큰과 두 번째 principal을 갱신하며 stale token은 자식 시작 전에 거부한다. 실제 PUT→본인 정보/기존 댓글 작성자 표시, 동일값 무변경·case-only/타인 중복·인증/검증/부재·UPDATE 권한 실패/회복을 확인한다. 성공은 해당 회원 nickname/updated_at만 바꾸고, 동일값·오류·GET은 전체 DB/S3 불변이다. 원본 JPA 비교는 별도 compatibility 시험이며 운영 HTTP E2E가 아니다.
15. 기존 F3~F13 staged 회귀 뒤 F14 owner/marker와 고정 V1 favorites4열·identity PK·회원/동물 UK·User RESTRICT FK·실제 animal index를 준비한다. reader에는 SELECT(user_id,animal_id)만 추가하고 writer의 favorites 권한·두 계정 sequence 권한은 금지한다. F13 strict 체인은 그대로 재사용하고 F12 strict는 호출하지 않는다. 실제 원본 derived exists와 후보를 전용 favoriteStatusCompatibilityTest UTC 한 번 및 비0/실패·오류·skip0 XML로 비교하며 시각 경로 없는 동일 사례를 Seoul에서 반복하지 않는다. JAR는 최종 갱신된 두 principal 토큰으로 true/false·회원 부재 false·인증/형식/SQL 실패와 권한 복구를 확인한다. 매 GET snapshot에 favorites를 포함해 전체 DB/S3 변경0, 정상 두 pool 종료·연결0을 검사하고 시험 행은 favorite→User 순서로 정리한다.
16. 기존 F3~F14 staged 회귀 뒤 F15 owner/marker와 고정 V9 전체 board2열·revision0 초기행/videos11열·UUID PK·영상ID UNIQUE/형식 CHECK·refresh index를 준비한다. reader에는 videos11열 SELECT만 부여하고 board 및 writer의 두 테이블 권한·identity/sequence는 추가하지 않는다. F14→F13의 기존 target/owner/ACL 체인을 재사용하며 F15 catalog 실패는 별도 태그의 bounded/masked 진단만 남긴다. 전용 homeVideoCompatibilityTest/homeVideoCompatibilitySeoulTest는 실제 원본 Service/Repository와 TSTZ cutoff−1µs/동일/+1µs·정렬/3건·nullable metadata/Instant JSON을 UTC/Seoul에서 비교하고 두 exact-FQN XML의 비0·실패/오류/skip0을 확인한다. Seoul JAR는 하루 전 fresh/31일 전 stale로 공개 성공·malformed Bearer·권한503/복구·빈 배열을 확인한다. 영상/board/favorites와 기존 전체 업무 DB/Outbox/S3 snapshot은 GET 전후 불변이며 두 pool 정상 종료·연결0을 검사한다. 공개 GET용 토큰 재발급/TTL 검사는 추가하지 않는다.
17. 기존 F3~F15 staged 회귀 뒤 F16 owner/marker와 고정 V11 중 daily_visit_count 한 CREATE만 준비한다. 정확한 DATE PK·BIGINT NOT NULL/검증된 nonnegative CHECK·reader 두 열 SELECT·writer0/no-sequence를 catalog로 검사하며 F15 safe chain을 재사용한다. visitTodayCompatibilityTest는 UTC data/access 두 클래스, visitTodayCompatibilitySeoulTest는 같은 DATE data 클래스만, visitTodayLegacyMvcTest는 실제 원본 MVC를 UTC에서 한 번 실행한다. 정확한4개 XML·비0·실패/오류/skip0을 확인하고 일반 compatibility에서 세 클래스를 제외한다. 공개 Seoul JAR는 true5 GET과 false1 GET404로 나누고 JWT를 읽거나 갱신하지 않는다. 소유 인접일 합계와 요청 전후 서울 날짜로 실시간 자정 오판을 막고 GET·시작 전후 전체 업무 DB/S3 불변과 앱별 두 pool 정상 종료·연결0을 확인한다. 비활성 방문 bean0/JDBC mock 무호출은 Boot 시험 근거이며 JAR404·snapshot 불변을 SQL 시도0 증거로 과장하지 않는다.
18. 기존 F3~F16 staged 회귀와 F16의 두 JAR phase를 끝낸 뒤 F17 owner/marker와 고정 V3 contact_deletions·V6 members/blocks CREATE·V8 blocked index만 추가한다. reader는 pending ID1/차단 ID2, members/시각/writer 권한0이며 이전 F16 safe chain과 정확한 PK/FK/CHECK/index를 검사한다. noteRecipientCompatibilityTest UTC 한 번에서 실제 원본 query/3개 원문 클래스+6메서드 wrapper 및 가벼운 원본 Feign/null 경계를 비교하고 정확한 두 XML의 비0·실패/오류/skip0을 확인한다. 마지막 PG 단계가 실제 원본 발급기로 필요한 두 principal/expired 키만 갱신하고 기존 token map·0600·owner를 보존한다. Seoul JAR는 새 소유 회원 둘만 시드해 정상4필드·self/양방향 차단/pending403·늦은0/음수401·회원/저장소503과 복구·인증 선행·미편입 거부를 검증한다. 모든 GET의 기존 전체 DB/새3표/S3 snapshot은 불변이며 blocks→pending→members→users의 소유 키만 정리한다. 두 pool 정상 종료·잔여 연결0과 로그의 합성 회원/인증값 비노출을 확인한다.
19. 기존 F3~F17 staged 회귀 뒤 F18은 F17의 owner/target/marker/catalog/ACL/full snapshot을 읽기 검증만 하고 fixture를 다시 만들지 않는다. 단일 blockedAuthorsCompatibilityTest UTC task에서 실제 원본 SQL과 최소 service/MVC/advice/filter를 두 exact FQN으로 비교하며 비0·실패/오류/skip0 및 예상 외 XML0을 검사한다. JAR 직전 PG 최종 단계가 실제 발급기로 F18_CALLER/같은 caller의 expired만 갱신하고 이전 token map·owner·0600을 보존한다. 사용하지 않는 이전 키의 TTL은 검사하지 않는다. Seoul 단일 JAR는 caller User1·member4·outgoing2/reverse1 block3만 시드하여 정렬/중복/역방향 제외·target User 불필요·빈 배열, 누락 envelope/빈 입력 service400/혼합 query 변환400, User 권한 회수 중 invalid0 선행400→회원503·차단 저장소503과 각 복원200을 검증한다. 모든 요청의 기존 전체 DB/새3표/S3 snapshot 불변·소유 FK 순서 정리·두 pool 정상 종료·연결0을 확인한다. 입력 전체 matrix는 가까운 MVC/단위 시험에 맡기고 새 flag나 Seoul PG 반복은 추가하지 않는다.
20. 기존 F3~F18 staged 회귀 뒤 F19는 F18/F17 fixture·owner·exact ACL·전체14표 snapshot을 변경 없이 재사용한다. totalUserCountCompatibilityTest UTC 하나에서 정확한 PG3·원본 MVC4사례를 두 XML로 확인하며 Seoul PG를 반복하지 않는다. 마지막 PG가 실제 발급기로 F19_ADMIN/F19_USER 두 사용키만600초 갱신하고 기존 키·0600을 보존한다. 각 인증 요청의 전체 snapshot을 마친 뒤 실제 요청 바로 직전에 두 F19 사용키의 ID·role·남은 TTL60초를 다시 검사하며, 무토큰401에는 이 선조건을 적용하지 않는다. Seoul JAR는 새 USER ID 한 행만 DB ROLE_ADMIN으로 시드하고 ADMIN ID는 DB 부재로 유지한다. ADMIN200→user_id SELECT 회수 중 USER+위조 role403·ADMIN500→finally 복원200, missing401·인접 admin GET 기본403의6요청만 관찰한다. 역할 전체 matrix는 가까운 Boot 시험이 책임진다. 회수 중에는 strict F17 owner 체인이 User ACL을 검사하므로 사용하지 않고 F2/F3 안전 대상과 F16/F17 marker identity만 확인해 소유가 맞을 때 복원한 뒤 full chain을 재확인한다. owner가 다르면 복원 SQL 전에 중단한다. 모든 요청의 전체 DB/S3 불변·성공한 소유 User만 정리·두 pool 정상 종료/연결0을 확인한다. count controller 원본은 명시 no-store가 없으므로 notes 정책을 복사하지 않으며 Security 캐시 헤더는 실제 Boot 관측값으로 고정한다. 실제 Boot HTTP27건에서200/401/403/500과 미편입403의 공통 no-cache, no-store, max-age=0, must-revalidate를 관측해 검사값으로 고정했다. standalone 원본 MVC의 명시 캐시 없음과 후보 Security의 공통 헤더를 같은 증거로 표현하지 않는다.
21. 기존 F3~F19 회귀를 원래 ACL로 마친 뒤 F20은 private_note_blocks.created_at SELECT 한 열만 추가한다. 새 schema/column/index/marker/writer/sequence는 없으며 확장 후 strict2열 f17/f18/f19.require_fixture를 재호출하거나 완화하지 않는다. F17 안전 owner/F16·User7 체인과 F19 owner reuse receipt를 보존하고 F20 exact3열·pending1/members0/writer0·원래 shape/PK/FK/CHECK/index를 별도 확인한다. noteBlockListCompatibilityTest UTC는 정확 PG/MVC 두 FQN, Seoul task는 같은 PG 클래스의 TSTZ→Instant 단일 메서드만 실행한다. 실제 원본-only page 바인딩12사례를 먼저 관측했고 최종 원본/후보 MVC14사례를 통과했다. UTC PG10과 Seoul 단일시각1은 hosted CI의 실제 XML 건수/실패·오류·skip0으로 검사하며, 자원 모의 guard만으로 PG/JAR 실행 성공을 주장하지 않는다. 마지막 실제 발급기는 F20_CALLER 키만600초 갱신하고 기존 모든 키·0600을 보존한다. 기존 expired/옛 토큰은 갱신·TTL 검사하지 않는다. 모든 인증 요청은 DB/S3 snapshot 완료 뒤 요청 바로 전 해당 키의 남은60초를 확인하며 무토큰401은 재검사를 생략한다. Seoul JAR10요청은 page0/1/2·10+2/빈 배열·동일 TSTZ microsecond/ID DESC·원문 공백/정상/탈퇴 표시, timestamp 회수 storage503/복원과 User nickname 회수 중 invalid page400→회원503/복원, missing401·POST 기본403을 확인한다. GET9=성공5/거부4와 route1이며 모든 요청의14표/S3 불변·성공한 소유4 User/14 member/13 block/1 pending만 FK 순서 정리·두 pool 정상 종료/연결0을 관찰한다. User 권한 회수 중에는 F19 marker-only identity로 소유 확인 후 GRANT를 복원하고 전체 F20 체인은 복원 후 검사한다. caller 실패를 batch SQL 실패라고 부르지 않으며 count 이후 list 실패/정확 batch phase는 가까운 PG 근거와 구분한다. 새 flag나 전체 Seoul page/ACL/오류 반복은 없다.
22. always cleanup은 현재 run owner label의 합성 컨테이너만 정지/제거한다. 일반 prune·다른 컨테이너/볼륨 조작은 하지 않는다.

`scripts/f2_ci.py`·`f3_ci.py`·`f4_ci.py`·`f5_ci.py`·`f6_ci.py`·`f7_ci.py`·`f8_ci.py`·`f9_ci.py`·`f10_ci.py`·`f11_ci.py`·`f12_ci.py`·`f13_ci.py`·`f14_ci.py`·`f15_ci.py`·`f16_ci.py`·`f17_ci.py`·`f18_ci.py`·`f19_ci.py`·`f20_ci.py`·`f3_s3_stub.py`의 실행 가드는 이 저장소 GitHub-hosted Linux와 고유 run ID를 요구한다. DB mutation 전에는 loopback/정확한 시험 DB/marker/admin/reader를 대조한다. 미설정 테스트를 skip 성공으로 처리하지 않는다. `scripts/f2_fixture.sql`·`f3_fixture.sql`·`f4_fixture.sql`·`f5_fixture.sql`·`f7_fixture.sql`·`f8_fixture.sql`·`f9_fixture.sql`·`f10_fixture.sql`·`f11_fixture.sql`·`f12_fixture.sql`·`f13_fixture.sql`·`f14_fixture.sql`·`f15_fixture.sql`·`f16_fixture.sql`·`f17_fixture.sql`은 CI 전용이며 앱 resource/JAR에 포함되지 않는다.

`prepare_legacy_oracle.py`는 기준 `e8ad7b8ec9006772ccc5f5881334df2730dbd8de`의 정확히 6개 Java 파일만 해시 검증해 boot/build 안 test-only 경로로 추출한다. Post/BoardType/StringListConverter/PostResponse와 UserServiceClient 및 그 JDK-only 응답 의존 PrivateNoteModels다. legacy 전체 sourceDir·Application을 편입하지 않는다. 실제 source/버전 비교는 compatibility classpath에서만 이뤄지고 앱 JAR에는 들어가지 않는다.

`prepare_f3_legacy_oracle.py`는 F3 기준 `0e0161e03ddb31981487c4d0ece280cb04c1107c`의 기존 JWT 발급기·User 의존 타입·Outbox·Nori 등 정확히 9개 파일을 해시 검증해 test-only 경로로 추출한다. 실제 JPA/발급기/분석기와 후보의 차이를 UTC·Seoul 양쪽에서 비교한다. 생성한 합성 토큰은 run 전용 0600 임시 파일에만 두고 artifact에서 제외한 뒤 정리한다.

`prepare_f4_legacy_oracle.py`는 고정 F3 기준의 Post·BoardType·StringListConverter·OutboxEvent 해시와 이미 추출한 F2/F3 클래스를 대조한다. 같은 FQCN을 다시 생성하지 않는다. legacy JPA auditing 비교는 CI 소유 관리자 연결만 사용하고 후보 writer의 최소 권한과 구분한다.

`prepare_f6_legacy_oracle.py`는 고정 `a57ad47970a6df3fdad1d29d5c269f1f4ba8fff7`의 실제 PostRepository와 컴파일에 필요한 통계 응답2개를 해시 검증해 test-only 경로에 추출한다. 공유 엔티티·변환기·응답은 기존 F2 생성물과 바이트를 대조해 중복 클래스를 만들지 않는다. 동률 시각군은 행 구성과 필드를 비교하고 군 내부 배열 순서를 새 보장으로 고정하지 않는다. 새 fixture/권한은 만들지 않으며 기존 hosted owner/marker 체인을 재사용한다.

`prepare_f7_legacy_oracle.py`는 main `73f2009e63d61dd66b2df365452075d71a1757b3`의 실제 `PostgresqlSearchService`와 필요한 검색 계약/오류 타입을 고정 해시로 최소 추출한다. 공유 F2 entity/DTO/client와 F3 Nori/입력 예외는 고정 해시·기존 생성물 바이트를 대조하고 같은 FQCN을 중복 생성하지 않는다. 실제 검색 SQL을 시험에 복사한 결과를 legacy oracle이라고 부르지 않는다. 생성물은 compatibility 전용이고 실행 JAR에서 제외한다.

`prepare_f8_legacy_oracle.py`는 F8 기준 `6c76989c3da6dd3d8641ee90fdd38517ce068341`의 실제 `Comment`, `CommentRepository`, `CommentResponse` 세 파일만 고정 해시로 검증한 뒤 전용 test-only 경로에 추출한다. 전체 legacy application이나 HTTP 서비스는 기동하지 않는다. 다른 출력 경로·입력 변조·기존 생성물 덮어쓰기를 거부하는 Python 검사는 DB·컨테이너·JVM 없이 실행한다.

`prepare_f9_legacy_oracle.py`는 기준 `acbb086ba14820cbba22a9055923158f08894011`의 실제 `CreateCommentRequest`만 새로 해시 검증해 전용 test-only 경로에 추출한다. 공유 F8 세 클래스는 같은 기준의 해시·이미 추출한 바이트를 대조하고 중복 생성하지 않는다. 실제 request validator·repository/entity/DTO·auditing 비교의 근거이며 legacy Controller·Service 전체 HTTP 실행이나 Feign 장애 처리까지 검증하는 oracle은 아니다.

`prepare_f11_legacy_oracle.py`는 기준 `0bad12b804ea5e90ed0db2d5109566e92978ea8d`의 실제 `UpdateCommentRequest`만 새로 해시 검증해 전용 test-only 경로에 추출한다. 공유 F8 세 클래스와 F9 요청은 같은 기준의 고정 해시·기존 generated bytes를 모두 대조하고 중복 생성하지 않는다. 실제 managed entity/repository/DTO·JPA auditing과 요청 validator의 비교이며 전체 legacy Controller·Service·Feign HTTP oracle은 아니다. 출력·evidence 경로와 공유/신규 입력 변조·기존 생성물 덮어쓰기는 무소켓 Python 검사로 거부한다.

`prepare_f12_legacy_oracle.py`는 기준 `5473c4ccd95ae974c79acc7030228d4a717af3b3`의 실제 UserInfoResponseDto 한 파일만 고정 해시로 추출한다. F3 공유 User/Role는 같은 source SHA의 해시와 기존 generated bytes를 대조하고 중복 생성하지 않는다. 전용 output/evidence 경로·공유/신규 변조·기존 생성물 덮어쓰기 거부를 무소켓 Python 검사로 보호한다. 다른 관리자 JPQL·DTO·OAuth·Kafka와 JPA 도구는 runtime/JAR에 편입하지 않는다.

`prepare_f13_legacy_oracle.py`는 고정 원본 User/Role와 요청 DTO·Repository·컴파일 의존 및 실제 updateNickname 메서드 원문으로 제한된 compatibility oracle을 구성한다. 관리자 JPQL 전체를 초기화하지 않으며 실제 중복 @Query와 inherited findById/save·auditing·proxy commit 경계를 비교한다. `f13_ci.py`는 고정 V1/V2 파일 해시와 실제 fixture DDL을 대조하며 전체 User charset CHECK의 생성 정의를 marker에 보관해 이후 변조도 거부한다. 신규 원본과 공유 generated bytes는 해시로 고정하고 앱 JAR에서 제외한다.

`prepare_f14_legacy_oracle.py`는 고정 Favorite/FavoriteRepository와 실제 MVC Controller/Advice·필요한 DTO 의존만 compatibility 전용으로 추출한다. 공유 User/Role·기존 오류 타입은 고정 해시와 generated bytes를 대조한다. 실제 derived exists는 ID projection에 favorite_id가 필요하므로 owned-admin read-only oracle와 후보의 reader 두 열 권한을 구분한다. Controller·Advice의 순수 MVC 비교는 DB 없이 실행한다. Advice의 Redis 예외 타입을 위한 spring-data-redis 한 artifact만 transitive 없이 compatibility에 두며 JAR 격리 검사에서 거부한다. 원본 전체 UserService/OAuth/Animal Feign·Redis 서버는 기동하지 않는다. `f14_ci.py`는 고정 V1 해시·실제 favorites DDL과 기존 User 행 보존을 확인한다.

`prepare_f15_legacy_oracle.py`는 기준 `cb0082881391b3ba885293520c2a6024a15a2034`의 실제 HomeVideoModels·Repository·Service·YouTubeVideoClient·YouTubeVideoUrl5파일을 고정 해시로 검증해 전용 compatibility 경로에 추출한다. 실제 home()를 고정 Clock과 JDBC로 실행하며 YouTube mock 무호출과 원본 home의 TX 무호출을 확인한다. ORM·전체 원본 앱·스케줄러·실제 HttpClient/외부요청은 실행하지 않는다. 출력/evidence 경로·변조·기존 출력 덮어쓰기 거부는 무소켓 guard로 보호하고 원본/fixture/시험 생성물은 실행 JAR에서 거부한다. `f15_ci.py`는 고정 V9 전체 DDL과 기존 업무 행 보존을 대조한다.

`prepare_f16_legacy_oracle.py`는 기준 `33cad5567767f8730fc2c4389815ecd031f41dc0`의 VisitService/Repository/Cookie/Models/Controller/Advice와 Community ResponseDTO/global advice/charset helper9개 실제 소스를 해시 고정한다. F7의 공통 오류 두 클래스는 원문 해시와 기존 generated bytes를 확인해 재사용한다. 실제 원본 GET과 scoped DAO503/global Runtime500의 body/headers를 최소 classpath에서 비교하고 원본 설정·lifecycle·예약은 등록하지 않는다. 원본 Cookie는 oracle에서만 합성 키로 생성하며 후보나 JAR는 키가 필요 없다. 경로·해시·공유 바이트·기존 출력 보존을 무소켓 guard로 확인하고 생성물은 JAR에서 제외한다. sparse checkout에 없는 원본 입력은 고정 커밋의 read-only Git 객체에서 해시를 검증해 읽으며 원본 디렉터리 존재나 로컬 전체 checkout을 전제하지 않는다. guard의 모의 자원 검증을 실제 PG/JAR 실행 근거로 표현하지 않는다.

`prepare_f17_legacy_oracle.py`는 고정 `33cad5567767f8730fc2c4389815ecd031f41dc0`의 원본9파일과 PrivateNoteService6메서드 원문을 해시 검증한다. 실제 User ContactMember/Query·Community PrivateNoteRepository3개와 명시적 시험용 wrapper만 생성하고 F2 Client/Models는 기존 generated bytes 대조 후 재사용한다. 전체 원본 service·앱·HTTP/Feign 전송을 실행한 oracle는 아니며 User query→Community DTO bridge의 전송 실패 의미와 실제 저장 SQL/정책을 구분한다. `f17_ci.py`는 착수 `17a8bc72caf9f3a46b52e133e0a46c492ae52614`의 변경 없는 전체 V3/V6/V8 해시와 최소 fixture 원문 조각을 대조한다. sparse checkout의 입력은 해시 고정 read-only Git 객체로 읽고 모든 입력/공유 바이트/메서드/기존 출력 검사를 쓰기 전에 수행한다. 무소켓 guard의 자원 모의를 실제 PG/JAR 증거로 표현하지 않으며 원본·fixture·생성물은 실행 JAR에서 제외한다.

`prepare_f18_legacy_oracle.py`는 고정 원본15파일·기존 shared11 FQN·선택12 method unit과 class mapping/scope/logger/Boundary 해시를 확인한다. 서비스/Controller 두 명시적 wrapper·scope만 적응한 원본 scoped advice·원본 Boundary 총4파일만 새로 생성하며 기존 F2/F3/F7/F16/F17 generated bytes를 중복하지 않는다. 모든 source/shared/method/output/evidence 충돌 검사를 쓰기 전에 끝내고 sparse checkout 입력은 read-only Git 객체로 읽는다. 실제 원본 MVC12 관측은 누락과 빈 입력, 반복과 CSV 혼합의 다른 바인딩을 구분하는 근거다. 전체 원본 앱·Gateway·실제 HTTP/Feign·DB 실행으로 과장하지 않으며 oracle와 공통 컴파일 의존은 compatibility 전용이다. F18 CI 가드의 자원 모의 역시 실제 PG/JAR 증거와 구분한다.

`prepare_f19_legacy_oracle.py`는 고정33cad55 원본13파일·shared8 FQN·선택9 unit/section을 검증하고 annotated count controller 한 파일과 receipt만 생성한다. 모든 기존 source/receipt 충돌을 쓰기 전에 거부하며 sparse 입력은 해시 고정 read-only Git 객체로 읽는다. 서비스의 read-only count 위임과 Gateway 역할 결정은 source evidence이고 전체 UserService proxy/Gateway 실행 주장이 아니다. 실제 inherited SimpleJpaRepository count는 별도 owned-admin RO helper, 후보 count(user_id)는 기존 restricted reader로 비교한다. 원본 User DTO/global advice는 기존 F14 bytes를 재사용하고0/Long.MAX_VALUE·DAO/Runtime500은 DB 없는 MVC4사례로 닫는다. 새 DDL/ACL/marker/SQL fixture가 없으며 legacy/JPA/Redis·test closure는 실행 JAR에서 거부한다. F19 Python 자원 모의·read-only Git 입력 가드는 실제 PG/JAR/JVM 성공 근거와 구분하고 변경 시험의 모든 메서드·보호 분기·실행 수를 인계한다.

`prepare_f20_legacy_oracle.py`는 고정2b160c07 원본17파일·공유 raw12와 F18 adapted advice1의 bytes를 확인하고 서비스12메서드/NOTE_PAGE_SIZE=10·controller 전체 annotation/default page·advice4/Order/logger의 LF를 고정한다. 고유 F20 service/controller/advice3개만 생성하고 기존 Repo/User 실제 getAll/DTO/Models/Boundary·F18 original-name advice는 덮거나 중복하지 않는다. advice의 고유 class/scope 적응 뒤에도 logger literal은 기존 F18 original-name class를 참조함을 검증·기록한다. 모든 source/shared/unit/FQN/output/receipt 충돌을 쓰기 전에 거부한다. User batch HTTP21제한·V6 TSTZ는 source evidence이며 실제 원본 whole service/SSE/Gateway/HTTP/Feign 실행을 뜻하지 않는다. sparse 입력은 해시 고정 read-only Git 객체로 읽고 자원 모의 guard는 실제 PG/JAR/JVM 증거로 세지 않는다. 원본/생성물/시험은 실행 JAR에서 제외하고 변경된 시험의 모든 메서드·보호 분기·실행 건수를 인계한다.

TestKit은 Gradle logging 충돌을 막기 위해 architectureTest 전용 classpath를 사용한다. 실제 업무 build와 guard를 fixture에 복사하고, 본 빌드가 해결한 BOM/의존 cache를 offline으로 사용한다. 허용/금지 fixture의 이유를 검사하며 일반 컴파일·다운로드 오류를 구조 차단 성공으로 취급하지 않는다.

Wrapper checksum 고정과 기존 최소 메모리·worker 1 설정을 유지한다. CI는 테스트 XML·source/runtime·oracle hash·packaged JAR hash/기동/종료를 7일 보존한다. Mac ARM64 운영 기동·전체 서비스 통합·RAM 절감의 증거와 구분한다.

F21은 F20 전체 stage 뒤 고정d436e3c의 V6 notes/mailboxes 두 표와 V7 새 두 표용3 index·V8 reply index만 격리 fixture에 추가한다. 전체 migration3개와 원문 fragment6개의 hash·기존 F17의 동일 hash key를 검증하고, source/owner/F20 receipt/출력 receipt 충돌·새 표 부재를 모두 확인한 뒤 한 TX로 추가한다. 기존 members/blocks/F20 exact3열 ACL과 소유 evidence를 재사용하며 requests·새 schema/marker/writer/sequence는 없다. 원본 notes9열/mailboxes6열·PK/FK/CHECK·각5 index·소유 postgres를 검사하고 reader는 notes4/mailboxes5열만 허용한다. 원본 n.*는 owned-admin reference에서 비교하며 candidate에게 body SELECT를 주지 않는다.

선행 F21은 PR176 CI39251475888에서 실제138 XML·1785건과 PG13/MVC14·Seoul1·JAR9를 검증했다. PG8메서드/UTC13·Seoul TSTZ1과 JAR9요청 분기를 검산해 예상 exact 계약으로 고정했다. 원본/후보 MVC2메서드14는 로컬에서 통과했다. 이 예상 수를 통과 수로 기록하지 않으며 실제 XML의 정확 FQN/건수·실패/오류/skip0과 JAR 관측 결과가 있어야 통과한다. 향후 값이 None/미정이면 results/smoke gate가 거부한다. 원본 binder는 실제12입력 관측 이후 기대값을 채웠다. noteNotificationsCompatibilityTest는 정확 PG/MVC 두 FQN, noteNotificationsCompatibilitySeoulTest는 PostgresqlNoteNotificationsCompatibilityTest의 지정 TSTZ 한 메서드만 실행한다. guard의 합성 건수와 자원 모의를 실제 PG/JAR/JVM 성공 근거로 표현하지 않는다.

F21 Seoul JAR는 소유6 member/5 User/26 note·26 mailbox/pending1만 seed하여 20+3/cursor·foreign cursor 빈 목록·nullable actor/탈퇴·원문 nickname/µs Instant·read/unread, expiry 권한503/복원·User nickname 회원503/복원·missing401·POST deny를 관찰한다. 실제9요청은 GET8(성공5/거부3)+POST1으로 확인했다. batch/unread의 정확한 실패 phase는 PG hook 근거이며 JAR 선행 caller/list 실패를 그 근거로 부르지 않는다. 마지막 PG가 실제 발급기로 F21_CALLER9975001만600초 발급하고 기존 키·0600을 보존한다. 매 인증 요청은 전체 snapshot 뒤 Authorization 설정 직전에 사용키 TTL60초만 재검사하며 요청마다 재발급하지 않고 무토큰 reload0이다. 회수 중에는 ACL 비의존 marker identity+exact F20/F21 receipt로 소유를 확인한 뒤에만 GRANT를 복원하고 full fixture는 복원 뒤 확인한다. 기존14+새2=정확16표/S3의 요청·기동 전후 불변, 자기 성공 INSERT 키만 mailbox→note→member/pending→User FK 순서 정리, private body/회원/인증값 비노출·두 pool 정상 종료/연결0을 확인한다. 기존 stage와 always cleanup은 보존하고 원본 lifecycle/예약/SSE/전용 전역 filter는 등록하지 않는다. 실제 PG/JAR 및 운영 검증 성공은 이 설명만으로 선언하지 않는다.

`scripts/f21_ci.py`도 기존 helper와 같은 GitHub-hosted Linux·정확한 target·run owner 제한을 사용한다. F21 DDL은 원본 Git 객체에서 최소 fragment를 조립하며 새 fixture SQL 파일/앱 resource를 만들지 않는다. 고정 catalog SELECT 실패만 F21 stage와 최대512자 첫 ERROR/FATAL을 기록하고 PASSWORD/SECRET/TOKEN 환경값을 지운다. SQL/DETAIL/command/stdout 전체를 기록하지 않으며 업무 SQL 실패는 원문 없이 중단한다. workflow는 고정d436e3c commit 객체를 명시적으로 확인하고 부재 시 일시적인 read token 환경으로 해당 객체만 fetch한다. sparse checkout은 backend-app과 workflow만 유지하고 새 generated oracle/compatibility 코드는 production JAR에서 거부한다. 기존 cleanup/artifact 보존은 그대로다.

`prepare_f21_legacy_oracle.py`의 원본19 full hash·공유 raw12/F18 adapted1과 선택11 service 메서드·annotated cursor controller·advice4/logger/LF를 guard로 보호한다. 잘못된 경로/늦은 source·shared·unit 변경/출력·receipt 충돌/중복 FQN은 쓰기 전에 거부하고, 성공 경로는 고유3 wrapper만 생성하며 공유13개를 보존한다. 이 source/모의 추출 guard도 실제 원본 whole app·SSE·Feign·PG를 실행했다는 뜻이 아니다. 로컬 full/minimal checkout에서 각34개 guard가 통과했고 Python3개 AST·workflow77개 실행블록 문법·nested JAR의 원본/시험/ORM·Feign 제외를 확인했다. 기존 Hibernate Validator는 보존한다.

## F22 본인 쪽지 단건 조회

F21 병합 dev `c3c2376c9abd418d9fe2ca2b55ebe6615aeab054`를 기준으로 편입한다. 로컬 User78·Community440·Boot703·MVC14=1235건, 원본-only path6 별도 관측, full/minimal checkout guard 각34건·구조/컴파일/실행 JAR 검사를 완료했다. 실제 PG24·Seoul1·JAR9는 hosted CI에서 확인하며 이 예상 수를 실제 통과 수로 합산하지 않는다.

F22는 기존 `GET /api/v1/notes/{id}`만 편입한다. caller 활성 확인 뒤 Clock1회→본인 mailbox+strict expiry 단건→nullable counterpart single→active INBOX에만 기존 양방향 block 조회를 수행한다. 응답은 원본 body/닉네임·nullable ID/context/readAt·Instant의 정확10필드이고 차단된 쪽지도200/canReply=false다. read_at/favorite·원문·요청/차단 상태를 쓰지 않으며 SSE·cleanup·새 User API·외곽 TX는 추가하지 않는다. UUID 변환·정적 경로 우선순위는 Boot/MVC가 책임진다. F22 당시 기본거부였던 exact `/stream`은 F26으로 인계하고, 선행 JAR 회귀는 `/stream/extra` 인접 경로 거부를 유지한다.

F21 모든 stage를 기존 notes4/mailboxes5 권한으로 끝낸 다음 F22는 recipient_id/body/context_type/context_id와 favorite의 SELECT5열만 확장한다. 새 DDL·표·인덱스·스키마·marker·writer/sequence 권한은0이다. 확장 뒤 F21 strict checker나 F21 READABLE monkeypatch는 사용하지 않는다. 기존 F20 User/blocks 검사를 유지하고 F21의 순수 constraint/index query만 재사용하여 원본 두 표 전체 shape/PK/FK/CHECK/index·owner postgres·notes8(reply_to 제외)/mailboxes6·writer0/no-sequence를 명시적으로 검사한다. 임시 body SELECT 회수 중에는 F19 marker identity와 exact F20/F21/F22 receipt 확인 뒤에만 GRANT하고, 복원 뒤 full 검사한다. 복원 실패는 passed result를 남기지 않는다.

`privateNoteReadCompatibilityTest`의 예상 PG는9메서드24case, MVC는3메서드14case(path6/payload4/failure4)이며 원본 path6을 먼저 관측해 provider6을 확정하고 원본/후보 MVC14의 실제 XML을 확인한 뒤 helper의 MVC count를 None에서14로 채웠다. 정상/대문자/축약 UUID는 실제 noteId로 변환돼 fixture 부재404, invalid/overflow/CSV는 서비스 도달 전400이다. 관측 임시 source/classes는 제거하고 private JSON·로그를 보존했다. PG24 XML은 별도 hosted gate다. Seoul task는 지정 TSTZ 한 메서드만1case다. 원본 owner-reference n.*와 후보 최소 reader 권한을 분리하며 counterpart/block의 특정 단계 실패 증거는 PG hook에 맡긴다. guard 합성 XML/HTTP·원본 source hash는 실제 PG/JVM/Gateway 실행을 뜻하지 않는다.

Seoul JAR는 성공 INSERT 이후 소유 User2/member3/note4/mailbox4 키만 사용한다. 예상9요청은 GET8(성공4/거부4)+PUT1이다: INBOX/SENT/null sender3성공, foreign404, body SELECT 회수503→복원성공1, missing JWT401, GET stream/extra403, PUT read/extra403. 모든 요청의 전후16표 전체 row·S3 snapshot, readAt/favorite 불변, 타입·body·null·µs JSON/no-store/no-cookie를 비교한다. 이 인접 stream/write 거부는 기존 보안 envelope/cache와 비교하며 F26 exact endpoint 거부를 뜻하지 않는다. 마지막 PG에서 실제 발급한 F22_CALLER9976001만600초로 갱신하고 기존 키/0600을 보존하며, 각 요청 snapshot 뒤 인증 직전 사용키 TTL60만 다시 검사한다. 무토큰 요청은 reload0이다. privatebody/nickname/email/토큰/비밀값 로그0·두 pool 정상 종료/연결0·mailbox→note→member→User FK 정리와 baseline 복귀를 검사한다.

새 source object `cba94f8`은 기존 F21 object와 별도로 확인·필요시 origin에 읽기 전용 fetch한다. GitHub read token은 URL/전역 config에 넣지 않고 한 명령의 ephemeral GIT_CONFIG만 사용하며 base64 파생값은 생성 직후 add-mask한다. sparse checkout에서 Git object의 원본19 full hash·공유 raw12/F18 adapted1·선택12 service/annotated get/advice4/logger LF와 고유3 output+receipt의 충돌 선검사를 보호한다. production JAR의 f22LegacyOracle/기존 원본/ORM·Feign·시험 코드 제외를 유지한다. catalog 실패는 첫 ERROR/FATAL 한 줄512자만 환경 PASSWORD/SECRET/TOKEN을 마스킹해 별도 진단 파일에 남기며 SQL/DETAIL/command/stdout/trace는 쓰지 않는다. Python3개 AST·workflow82개 실행블록 문법·실제 원본 추출/관측·비교MVC14와 full/minimal guard34가 통과했다. 실제 PG·JAR는 이 로컬 근거로 성공 처리하지 않는다.

## F23 본인 쪽지 목록 조회

기존 `GET /api/v1/notes`의 받은/보낸 쪽지와 즐겨찾기 페이지를 편입한다. page0..10000 → 정확한 INBOX/SENT → 현재 회원 → Clock1회 → count → 최대10행 목록 → nonnull 상대의 최초 순 distinct batch1회 → active INBOX 행마다 양방향 차단 순서다. count0이어도 목록을 읽고 요청 page를 보존한다. 정렬은 mailbox.created_at와 PostgreSQL UUID DESC, 응답 createdAt은 note.created_at이다. Page는 정확5필드, 행은 기존 단건과 같은10필드이며 raw body/닉네임·nullable ID/readAt/context·Instant를 그대로 돌려준다. 조회로 읽음·즐겨찾기·쪽지·차단을 쓰지 않는다.

기존 F22 single reader SAM·단건 조립·User single/batch·block reader·Clock·두 pool을 유지하고 목록 전용 두 SQL adapter만 추가한다. count와 list는 각각 기존 RO3초 TX를 반환한 뒤 다음 단계로 간다. 외곽 TX·재시도·pair cache를 추가하지 않으므로 count/list 사이 원래 경쟁 창을 닫았다고 주장하지 않는다. 최대10 active INBOX의 source상 DB 호출 상한은14이며 실제 소유 PG 시험으로 별도 확인한다. 기존 exact root GET 거부2항목만 새 목록 계약에 인계했다. F26에 인계한 exact stream/읽음 PUT 외 나머지 쓰기·ADMIN·단건/static 경계와 stream/extra 인접 거부는 유지한다.

F22 전체 stage 뒤 `f23_ci.py prepare`는 현재 notes8/mailboxes6·blocks3 reader ACL과16표를 변경 없이 검증한다. 새 DDL·표·권한·writer·sequence·marker는0이다. `privateNoteListCompatibilityTest`는 UTC 원본 PG와 MVC exact2클래스, Seoul task는 지정 UUID정렬/Instant1메서드만 실행한다. SELECT 회수 중 복원은 기존 identity/F20·F21·F22 receipts를 먼저 확인하고 GRANT 후에만 strict를 재검사한다. count/list/batch/block의 실제 실패 단계는 PG가 맡고, JAR의 선행 실패를 late batch 실패로 확대하지 않는다.

단일 Seoul JAR의 계획12요청은 목록GET9(성공6·거절3), 기존 detail GET1·stream/extra 인접 거부1·POST 거부1이다. 본인 INBOX12행의10/2/빈 page·SENT·favorites·page 우선400·mailbox timestamp SELECT503/복구·auth 선행을 확인한다. 마지막 PG의 실제 발급기로 F23_CALLER9977001만600초 발급하고 이전 키·0600을 보존한다. 각 요청 snapshot 뒤 사용키 TTL60초를 확인하며 무토큰에는 loader를 호출하지 않는다. 16표/S3 불변·성공한 자기 키만 FK 순 정리·두 pool 정상 종료/연결0을 검사한다.

`prepare_f23_legacy_oracle.py`는 고정 원본 `f06a72a06bd26662c647bde780dfc7a27bf7349c`의 full19·공유 raw12/F18 adapted1·service14/annotated list1/advice4와 NOTE_PAGE_SIZE10/LF/logger를 검증한다. 고유 service/controller/advice3만 생성하고 모든 source/shared/unit/FQN/output/receipt 충돌을 쓰기 전에 거부한다. 원본 SQL/User getAll을 실제 호출하는 PG와 source-only DDL/index 근거를 구분한다. 원본 query6은 별도 actual MVC/compiled metadata를 먼저 관측하고 provider를 확정해야 한다. 미측정 `None`/null은 fail-fast이며 mock guard나 준비된 계수를 실제 실행 결과로 세지 않는다. 원본·fixture·시험·ORM/Feign은 production JAR에서 제외한다. F22가30분 상한에 도달한 이전 실측과 신규 목록 PG/MVC/JAR 단계를 고려해 이 격리 check job의 상한만35→40분으로 제한 조정한다. selection5분/result2분·검사/정리/발행 차단은 보존한다. F23 실제 시간·성공 근거를 뜻하지 않는다.

현재는 고정 b64ad29 사본에서 원본 관측6·원본/후보 MVC12·호환 컴파일·User78/Community469/Boot731 합1278과 비교MVC12 합1290을 실제 확인했다. 임시 observer source/classes는 제거했다. 실제 기능 브랜치 기준과 hosted PG16/Seoul1/JAR12·최종 CI는 별도 gate이며 아직 성공으로 세지 않는다. 전체 결정과 후속 실행 결과는 Obsidian의 F23 계획에서 구분한다.

## F24 본인 쪽지 차단 해제

기존 `DELETE /api/v1/notes/blocks/{member}`만 편입한다. current와 target 두 ID가 양수인지 TX 전에 확인하고, 어느 쪽이든0/음수면401이다. TX15초 안에서 sorted distinct ID마다 INSERT 뒤 같은 row FOR UPDATE를 수행한 다음 현재 회원 활성 조회1회와 outgoing DELETE1회를 실행한다. self·없는 차단·탈퇴/부재 target도 성공할 수 있으며 target User 조회는 없다. 성공은 빈200/no-store/no-cookie다. 없는 target의 member 행 INSERT는 허용된 변경이므로 전체 DB 무변경으로 표현하지 않는다. current 검증 실패 때 앞선 INSERT는 rollback되어야 한다.

application의 좁은 동기 callback을 adapter가 잠금 뒤 정확1회 호출한다. User 정책은 application에 두며 callback 저장·비동기·재시도·예외 삼킴은 없다. 기존 writeJdbc와 writeTransactionManager를 재사용하고 이 operation 전용15초 TransactionTemplate만 조립한다. 같은 datasource의 활성 TX 잔여시간이 기존 queryTimeout5를 덮으므로 새 JdbcTemplate·pool·TM를 추가하지 않고 기존 공통10초 설정도 바꾸지 않는다. Clock·SSE·afterCommit·Outbox·새 User API는 없다. 원본 쓰기 오류 정책대로 typed User는 회원503, DAO만 저장소503이며 구체 TX3타입을 포함한 다른 Runtime은 일반503이다. scoped 로그에는 타입만 남기고 throwable·SQL·회원값을 전달하지 않는다.

F23 전체 smoke까지 기존 strict·writer0 검사를 완료한 뒤 `f24_ci.py prepare`가 기존 members의 SELECT/INSERT/UPDATE(member_id), blocks의 SELECT(member_id,blocked_id)와 DELETE만 추가한다. 새 DDL·표·스키마·marker·sequence 권한은0이다. 확장 뒤 선행 writer0 checker를 다시 호출하거나 monkeypatch하지 않고 F24 명시 검사로 원본 shape/PK/FK/CHECK/index·postgres 소유·reader/최소 writer ACL을 확인한다. 회수 중에는 ACL 비의존 identity와 F20/F21/F22/F23/F24 exact receipts로 소유를 확인한 뒤 GRANT하고 복원 후에만 strict를 확인한다. 복원 실패를 passed로 처리하지 않는다.

`prepare_f24_legacy_oracle.py`는 F23와 같은 고정 `f06a72a06bd26662c647bde780dfc7a27bf7349c`의 full19·공유 raw12/F18 adapted1·원문 service6/annotated DELETE1/advice4의11개 method receipt와 constructor/TX/logger/LF를 확인한다. 고유 wrapper3개와 receipt의 모든 충돌을 쓰기 전에 검사한다. workflow는 이미 F23가 준비한 동일 commit 객체를 추출 전 cat-file로 확인하며 추가 fetch·token·Git config·sparse 원본 경로를 만들지 않는다. 원본/생성물/시험/ORM·Feign의 실행 JAR 제외에 f24LegacyOracle만 더하고 기존 Hibernate Validator는 유지한다. 선택 원문과 기록 JDBC/MVC 근거를 원본 whole app·실제 Feign/Gateway·PG 실행으로 주장하지 않는다.

`noteBlockRemoveCompatibilityTest`는 UTC의 정확 PG/MVC 두 FQN만 실행한다. 원본은 실제 Hibernate/JpaTM reference와 bounded 별도 User thread를 사용하고, 후보는 실제 Jdbc writer TM 안에서 User RO TM 종료 후 reader가 반환되며 writer가 계속 활성인지 비교한다. 초기 JPA metadata 연결과 요청 연결을 구분한다. 최소 writer ACL·부분 INSERT rollback·commit 오류 분류·실제 잠금 대기와 소유 키 정리는 hosted PG의 책임이다. 원본 대표6입력 관측과 원본/후보 MVC14는 고정 소스 사본의 실제 로컬 XML에서 실패·오류·skip0을 확인하고 provider·helper14를 채웠다. 예상 PG14는 아직 hosted XML 통과 수가 아니며 실제 PG/JAR 결과를 로컬 MVC로 대체하지 않는다. 시각이 없는 해제에 Seoul PG matrix를 추가하지 않는다. Gradle의 `f24.fixture.receipt`는 같은 run의 `build/ci/f24-fixture-write.txt`를 전달한다.

기존 Seoul 자원 관리로 실제 JAR의 계획12요청을 수행한다: DELETE8(성공5/거부3), 차단 목록 GET2, stream/extra GET 거부1, target/extra PUT 거부1이다. 읽기→해제→읽기·중복 해제·없는 target의 member 생성과 DELETE 권한 저장소503/복원·User nickname 권한 회원503/복원·인증 선행을 비교한다. 각 요청은16표/S3 snapshot 뒤 사용키 TTL60초를 확인하고 무토큰 reload는0이다. 마지막 PG에서 F24_CALLER9978001만 실제 발급기로600초 갱신하며 이전 키와0600을 보존한다. 요청별로 outgoing 차단1행 삭제와 없는 target member1행 추가만 허용하고 나머지 row/S3·private body/회원값/비밀 로그 불변을 검사한다. 성공 INSERT 소유 키만 FK 순 정리하며 두 child의 실패 시 종료, 두 pool 정상 종료/잔여 연결0, 시작 전16표로 복귀를 확인한다.

workflow는 F23 smoke → 무자원 F24 guard → prepare → UTC PG/MVC → exact XML results → JAR smoke 순서를 지키며 기존 always owned cleanup·증거 보존·발행/배포 차단을 유지한다. 선행 F23의 check 상한40분을 그대로 유지한다. F22의 이전30분 취소 및 F23 추가 단계에 따른35→40 조정과 F24의 실제 실행시간을 구분하며, 아직 실제 F24 실행시간·성공 근거는 아니다. 추가 상향은 실제 선행 측정 후 별도 검토한다. F24 기능 브랜치의 base는 F23 병합 뒤 재확인하며, 아래 고정 사본 관측은 실제 기능 브랜치 생성 전의 준비 기록이다. 고정 b64ad29+F23 사본에서 원본 관측6·비교MVC14를 실제 통과했고 호환 컴파일도 완료했다. 이 로컬 선택 계약 관측은 실제 PG/JAR·CI·운영 검증을 대신하지 않으며, 실제 feature head의 결과는 해당 run 산출물로 확인한다.

## F25 본인 outgoing 쪽지 차단 생성 준비

기존 `PUT /api/v1/notes/blocks/{member}` 한 계약의 private 준비다. 두 ID 양수 검증 → positive SELF 거부 → writer TX15 → sorted distinct member마다 INSERT 다음 같은 row FOR UPDATE → current active → target active → Clock.instant 정확1회 → outgoing INSERT ON CONFLICT DO NOTHING → commit 순서를 보존한다. ID0/음수는401, 양수 self는 TX/User/Clock 전에400이며 current 실패는 target/Clock, target 실패는 Clock를 호출하지 않는다. 중복 요청도 잠금·User2회·Clock1회를 수행하고 빈200/no-store/no-cookie로 끝나지만 기존 created_at은 갱신하지 않는다. 없는 target도 성공하는 F24 해제와 달리 F25 target missing/pending은403이며 앞선 member INSERT는 rollback되어야 한다.

application `CreateNoteBlock`이 두 회원 활성 정책과 Clock를 소유하고, `NoteBlockCreation`의 좁은 동기 Supplier가 잠금 뒤 그 순서를 실행한다. 기존 F24 Runnable SAM이나 User single/batch API는 바꾸지 않는다. Boot의4bean은 기존 writeJdbc/writerTM 위 operation 전용 TT15와 기존 readClock를 재사용한다. 공통 TT10/JDBC5·User RO3·두 pool을 유지하며 새 datasource/TM/Clock·외곽 TX·SSE·afterCommit·Outbox는 없다. typed User는 회원503, DAO만 저장소503, 구체 TX3·usage·Clock/기타 Runtime은 일반503이다. 실제 commit이 DAO로 번역되면 실제 타입대로 처리하며 phase만으로 분류하지 않는다. scoped 로그에는 타입만 남긴다.

F24 마지막 strict/PG/MVC/JAR를 마친 뒤 `f25_ci.py prepare`가 blocks의 INSERT(member_id,blocked_id,created_at)만 추가한다. 기존 members SELECT/INSERT/UPDATE(member_id)와 blocks SELECT두ID/DELETE, reader User7/pending1/blocks3/notes8/mailboxes6을 보존한다. writer created_at SELECT/UPDATE·새 schema/표/업무 DDL/index/marker/sequence 권한은0이다. 확장 뒤 INSERT0을 요구하는 F24 strict를 재호출하거나 약화하지 않고 F25 exact shape/PK/FK/CHECK/index/owner/ACL 검사를 사용한다. 회수 중에는 ACL 비의존 identity와 선행/F25 exact receipts로 소유를 확인한 뒤 GRANT하고 복원 뒤에만 strict를 검사한다. 부분 grant·복원·정리 실패를 passed로 처리하지 않는다.

`prepare_f25_legacy_oracle.py`는 이미 F23에서 확보한 고정 `f06a72a06bd26662c647bde780dfc7a27bf7349c`의 full19·공유 raw12/F18 adapted advice1과 service6/annotated PUT1/advice4의11개 method receipt를 검증한다. constructor의 TransactionOperations/TT15·Clock 배선과 선택17개 LF 원문을 고정하고 고유 service/controller/advice3개만 생성한다. 모든 source/shared/unit/FQN/output/receipt 충돌은 쓰기 전에 거부한다. workflow의 추가 source 단계는 같은 commit의 cat-file 확인만 수행하며 새 fetch·token·Git config는 없다. f25LegacyOracle와 F25LegacyNoteBlockCreate 고유3종도 production JAR 제외에 추가하고 기존 Hibernate Validator 허용은 보존한다. 기록 TM/JDBC·User mock·중복 map은 호출 순서/전파 근거이며 실제 SQL·잠금·rollback/commit·Feign 실행 근거가 아니다.

`noteBlockCreateCompatibilityTest`는 UTC exact PG/MVC2클래스, `noteBlockCreateCompatibilitySeoulTest`는 같은 PG class의 `givenTstzMicrosecondsAndALaterDuplicateClock__whenBothCreateAndSerializeInEitherZone__thenStoreTheSameInstantAndKeepTheFirstTimestampWithoutLocalShift`1메서드만 실행한다. 두 task는 같은 run의 `f24.fixture.receipt`와 `f25.fixture.receipt`를 받는다. PG9메서드13사례와 Seoul1은 아직 실제 PG 통과 수가 아니다. 고정 사본의 원본 path7 관측 뒤 provider를 채운 MVC4메서드17사례는 아래 로컬 기록으로 구분한다. helper의 UTC PG 기대값13은 소스의 지정 사례 수이며 아직 실제 PG 실행값이 아니다. MVC 기대값17은 아래 원본/후보 비교 XML 실측을 반영했고, Seoul은 지정 메서드1건만 요구한다. results/JAR는 각 보고서의 사례 수·실패·오류·skip·실행 범위를 검사한다. MVC17 실측을 PG/JAR 실측으로 대체하지 않는다. 전체 정책의 Seoul 재실행은 하지 않는다. 원본 actual Hibernate/JpaTM/TT15와 bounded 별도 User thread, 후보 actual Jdbc writerTM와 User RO3의 서로 다른 실행 경계를 PG에서 비교한다. 두 User 호출 각각 reader 반환/계속 활성 writer, 최소 ACL·late 실패/rollback·lock 대기·실제 commit 번역·TSTZ와 중복 시각 보존은 아직 hosted gate다.

단일 Seoul JAR의 현재 정적 계획은18요청(PUT8: 성공4/거절4, GET7, DELETE3)이다. 생성 후 기존 차단 목록/상태/recipient의 결과, 중복 시각 불변, F24 DELETE 복구와 대표 INSERT/User503·복구·인증/인접 거부만 맡긴다. 허용 delta가 있는6요청·중복1·저장소/회원 실패 각1·복구2·인증1·인접 거부2를 helper와 workflow가 비교하며, 이는 실제 JAR 성공 수가 아니다. 마지막 PG에서 F25 사용키만 원본 발급기로600초 갱신하고 기존 전체 키/0600을 보존한다. 각 인증 요청은16표/S3 snapshot 다음 요청 직전에 사용하는 키 TTL60초를 다시 확인하고 무토큰에는 reload하지 않는다. outgoing INSERT(또는 중복0)·소유 member 생성/기존 해제에 필요한 변경만 허용하며 나머지 row/column/S3·private body/비밀 로그를 보호한다. 성공 소유 키·관측 member만 FK 순 정리하고 두 child 실패 종료·두 pool 정상 종료/잔여 연결0·초기 상태 복귀를 확인한다. JAR caller 실패를 늦은 target/commit/lock 실패의 증거로 확대하지 않는다.

기존 거부 세 곳만 새 계약에 인계한다. Boot Remove HTTP의 PUT target403은 새 성공 계약으로, Status HTTP의 PUT status403은 새 long binding400으로 옮긴다. 선행 F24 JAR의 PUT target403은 미편입 target/extra403으로 교체해 기존12요청과 보호 snapshot을 유지한다. 이 F24 helper/guard 변경은 해당 담당자가 맡는다. F20 PUT blocks root403·stream/extra 기본403·나머지 미편입 mutation은 보존한다. exact stream과 읽음 PUT의 정상 계약은 F26에서 별도로 보호한다.

고정 b64ad29+F23/F24/F25 소스 사본의 로컬 기록(`/private/tmp/pawbridge-f25-local-evidence-20261019.md`)에서는 User78/Community506/Boot781 합1365건·101XML과 구조71을 확인했다. 원본 path7 관측 JSON 지문은 `7f4cea6e1da726d9b4fa54670ab08c1a9592773a337546965ba5ff654b9ec871`이며 실제 관측으로 provider7을 채운 비교 MVC17건/1XML이 실패·오류·skip0이다. 일반1365+비교17 합1382와 임시 observer7/구조71은 분리한다. 후보 JAR의 생성 application/controller 존재와 원본·시험·ORM/Feign 포함0도 이 사본의 패키징 증거다. 이는 기록 JDBC/TM+mock User 비교로, 최종 PG/Gradle 준비 통합이나 실제 JpaTM/SQL/잠금/ACL/JAR HTTP 통과가 아니다.

workflow는 F24 전체 smoke → F25 무자원 guard → INSERT3 prepare → UTC/Seoul thin → exact XML results → 단일 JAR smoke 순서다. always owned cleanup·bounded 실패 증거·발행/배포 차단·contents read·고정 runner와 selection5분/result2분을 보존한다. check 상한은40분 그대로며 F24/F25 실제 소요시간을 아직 측정하지 않았다. artifact에는 `f25-results.json`만 명시 추가하고 token이나 모든 JSON glob을 넣지 않는다. 이 로컬 준비 기록은 CI 연결을 정적으로 검토한 결과이며, 실제 feature head의 PG/JAR·CI 결과는 해당 run 산출물로 확인한다. 기능 브랜치 생성은 F24 실제 PG/JAR/CI와 dev 병합 뒤 fresh base를 확인하는 조건을 따른다. 로컬 관측·비교·컴파일과 운영 검증을 구분한다.

## F26 본인 쪽지 읽음과 본인 SSE resync 준비

기존 `PUT /api/v1/notes/{id}/read`와 `GET /api/v1/notes/stream?clientId={UUID}`를 같은 사용자 결과로 준비한다. 본인 읽음 commit 뒤 본인의 다른 연결에 resync를 보내고, 기존 알림/단건/목록 REST를 다시 조회해 readAt·unreadCount를 확인한다. rollback·commit 실패에는 알림을 보내지 않는다. 기존 send/withdraw가 다른 JVM에서 실행되면 이 registry에 자동으로 전달되지 않으므로 전체 실시간 알림·Gateway 전환 완료로 표현하지 않는다. 실제 F26 feature base와 게시/병합은 부모 F25의 실제 검증·dev 병합 뒤 확인하는 별도 조건이다.

읽음은 TX15 → current member INSERT/FOR UPDATE → callback 안 requireId/active → 본인 mailbox JOIN·strict expiry 조회(clock1) → note lock → 동일 소유/만료 재조회(clock2) → INBOX `read_at=COALESCE(read_at,?)` UPDATE(clock3) → afterCommit resync 순서다. ID 검증을 TX 앞으로 옮기지 않아 비양수 direct call의 member CHECK DAO 선행과 HTTP JWT 양수 guard를 구분한다. 반복 INBOX도 UPDATE/세 번째 시각을 수행하지만 첫 readAt을 보존하고, SENT는 영향0으로 성공·resync한다. 세 번째 expiry 검사나 상대/차단 조회를 추가하지 않는다. app의 좁은 MissingOwnedNote만404로 바꾸며 typed User는 회원503, DAO는 저장소503, 그 밖의 구체 TX/Runtime은 일반503이다. 실제 commit에서 DAO로 번역되면 실제 타입을 따른다. 등록 실패·SQL·TX 예외는 전파하고, afterCommit 내부 hints의 Runtime만 타입 로그 후 성공을 유지한다.

Boot의 정확한 두 matcher를 같은 인증 filter/인가에 넣고 기존 ADMIN 우선·다른 경로 기본거부를 보존한다. 한 번 검증한 같은 JWT Claims의 exp를 JDK Instant details로 전달하며 헤더의 사용자/만료값을 신뢰하지 않는다. 기존 REST의 exp 선택 정책은 보존하고 SSE만 exp를 필수로 한다. 같은 요청의 ASYNC dispatch는 request-attribute context를 복원하며 새 연결은 새 JWT를 검증한다. 세션·raw token 저장·일반 REST context save·전역 ASYNC permitAll은 추가하지 않는다. 같은 registry가 읽음 hints를 구현하고 기존 readClock/writeJdbc/writerTM의 operation 전용 TT15를 조립한다. User API·두 pool/두 TM·공통 TT10/JDBC5는 유지한다.

registry의 원문14개 선택 메서드와 writeLock 안 expiry 재확인·교체 identity를 보존한다. 연결 TTL은 JWT 잔여와5분 중 작은 값, heartbeat15초, 회원 logical3/pending replacement3·전체 physical500, write2/queue256·completion2/queue500이다. 종료는 서버의 열린 SSE 대기보다 먼저 실행하며 공유 monotonic5초 예산 안에서 소유 executor 종료를 확인한다. `isRunning=false`·daemon·pool 종료만으로 SSE 자원 종료를 선언하지 않는다. 실제 JAR gate는 성공 로그와 열린 연결의 server-side EOF, `stream_resources_stopped=true`, `server_closed_open_streams=2`, 두 pool 정상 종료·잔여 연결0을 함께 요구한다. 미완전 종료 경고는 성공 근거가 아니다. 원본 scheduler·expire cleanup·send/withdraw API는 등록하지 않는다.

F25 전체 guard/prepare/PG/MVC/Seoul thin/JAR 뒤에 F26 fixture guard와 별도 stream probe guard → `f26_ci.py prepare` → `noteReadStreamCompatibilityTest` UTC와 `noteReadStreamCompatibilitySeoulTest` thin → exact XML results → 단일 Seoul JAR smoke를 실행하도록 연결한다. prepare는 마지막 F25 full strict 뒤 고정 f06 원문20개를 read-only Git object/hash로 확인하고 notes의 SELECT(note_id,expires_at)/UPDATE(note_id)와 mailboxes의 SELECT(member_id,note_id,direction,read_at)/UPDATE(read_at)만 원자적으로 추가한다. note_id UPDATE 권한은 FOR UPDATE 잠금용이며 실제 note_id 갱신 SQL을 추가하지 않는다. reader·기존 members/blocks writer·전체16표를 보존하고 새 DDL/표/schema/index/marker/sequence/PG image는0이다. 확장 뒤 F25 writer-zero strict나 monkeypatch를 쓰지 않고 명시한5표의 shape/default/owner/PK/FK/CHECK/index와 exact ACL을 검사한다. read_at UPDATE·User nickname SELECT 회수 중 복원은 ACL 비의존 identity와 선행/F26 exact receipts를 먼저 확인하고 GRANT 뒤에만 full strict를 실행한다.

원본 pin은 `f06a72a06bd26662c647bde780dfc7a27bf7349c`다. F23에서 확보한 commit을 cat-file로 재확인하며 추가 fetch/token/Git config는 없다. `prepare_f26_legacy_oracle.py`는 full20·공유 raw12/F18 adapted advice1·선택 원문/LF/constructor/TX15/Clock/afterCommit를 고정하고, 고유 F26LegacyNoteRead service/controller/advice3과 full-byte 원본 `com.pawbridge.communityservice.contact.PrivateNoteStream`1을 생성한다. F26LegacyNoteStream이라는 rename 타입은 없다. 모든 source/shared/unit/FQN/output/receipt 충돌은 쓰기 전에 거부한다. 결과 검사는 실제 oracle receipt의 source/shared/generated 파일 지문을 대조하며, 선택 메서드 원문/LF 검사는 추출기 책임이다. 이 test-only output4와 `f26LegacyOracle`·기존 원본/시험/ORM·Feign의 production JAR 제외를 유지한다.

기능 브랜치 생성 전 고정 사본에서는 원본만의 read/stream 입력12개를 실제 관측한 뒤 provider11행을 채우고 비교 MVC20건을 통과했다. 정상 stream은 Spring emitter의 controlled ready1을 전송해 실제 초기 응답 헤더를 관측했으며, 이는 원문 registry·소켓·JWT 실행을 뜻하지 않는다. 원본의 malformed SSE 요청400은 빈 본문/no-store, 일반 Accept400은 message JSON/no-store다. 이 차이를 보호하는 회귀 시험에서 기존 후보의 SSE 오류 cache 헤더 차이1건을 재현하고 scoped handler가 응답 commit 전에 no-store를 선적용하도록 보완했다. commit 뒤 헤더·본문은 바꾸지 않는다. 일반1430건과 구조71건, 호환 컴파일·bootJar도 같은 고정 사본에서 통과했으며 미래 실제 feature head나 운영 성공으로 확대하지 않는다.

PG19건·지정 Seoul1은 소스/provider를 전수 검산한 정확 기대 수로서 아직 실제 PG 성공이 아니다. helper는 UTC exact2FQN(PG19/MVC20), 실패/오류/skip/숨은 실패 child0와 지정 Seoul1을 실제 XML에서 요구한다. runtime22요청 카운터도 정적 계약이며 실제 JAR 결과로 검증해야 한다. 새 fixture21가드와 runtime22가드는 전체/최소44입력 사본에서 각각 통과했고 기존 전체 CI가드와 합친532건은 모의 제어 검증이다. 일반 정책 전체를 Seoul에서 반복하지 않는다. 기록 executor/TM·MockMvc emitter·SQL/자원 mock guard는 actual PG 잠금/commit·servlet/socket/SSE·정상 종료의 대체 근거가 아니다. Boot/stream 변경 목록은 선행 목록 HTTP 인계와 SSE Accept 회귀를 포함해24 Java/test14·36변경 메서드·101영향 사례(기존56/순증45)이며 전체 신규 시험수와 구분한다.

JAR probe는 실제 발급기 F26_CALLER/F26_OTHER의600초 토큰과 기존 전체 키/regular0600을 보존하고, 각 snapshot 뒤 요청/연결 직전 실제 사용키 TTL60초를 확인한다. 짧은 수명·exp없음·expired 입력은 명시한 합성 입력으로 분리하며 짧은 토큰은 파일에 저장하지 않는다. 본인 ready→read commit→resync→REST 재조회, 타인 resync0, 만료·재연결·권한 실패/복원·인증 선행과 전체16표/S3의 승인 delta만 검사한다. 현재 runtime 담당자의 정적 계약은 HTTP22회=PUT7(성공5/거부2)+GET15이며 nonstreaming19에는 거부 SSE3이 포함되고 성공 SSE3을 별도로 센다. 이는 실제 성공 건수가 아니다. 요청/event 계수는 runtime helper와 prefix의 검토된 counter 계약을 일치시킨 뒤 결과에서 exact 비교한다. 실제 JAR 성공 수는 아직 없다. 성공 소유 키만 FK 순으로 정리하고 `owned_cleanup_restored=true`, protected_database_changes/S3요청0·두 child/열린 SSE 종료·두 pool/연결0을 확인한다.

기존 F22~F25 Python JAR의 exact stream403 네 호출과 F22 exact read403 한 호출은 각각 GET `/stream/extra`와 PUT `/{id}/read/extra`의 기존403 envelope/cache 거부로 인계한다. 요청수·카운터·TTL·snapshot·cleanup은 유지한다. F22/F23의 계승 필드 `stream_denied_checks`는 이제 이 인접 경로 거부를 센다. exact stream 자체가 여전히 거부된다는 뜻이 아니다. Boot도 선행 목록 HTTP를 포함한 기존 거부 provider만 같은 깊은 경로로 인계하고 새 exact endpoint의 정상/실패는 새 계약의 가까운 시험에서 보호한다.

always owned cleanup·실패 증거 보존·발행/배포 차단·contents read·고정 runner와 selection5분/result2분을 보존한다. 완료한 F25 실제 CI validator가39분18초(run39285667745)였고 새 F26 PG·MVC·SSE 정상종료 단계를 더하므로 이 격리 check job 상한만40→45분으로 조정한다. selection5분/result2분·각 단계의 제한과 always 소유 정리·증거 보존·발행 차단은 유지한다.45분은 F26 실제 실행시간 측정값이나 후속 F27까지 충분하다는 보장이 아니며 다음 단계는 실제 tail을 다시 확인한다. artifact에는 `f26-results.json`만 명시 추가하고 token·모든 JSON glob은 넣지 않는다. 실제 feature head의 관측/PG/JAR·CI 결과는 해당 run 산출물로 별도 확인한다.

전체 결정·계획·문제 해결 기록은 Obsidian `Projects/pawbridge`의 F2·F3·F4·F5·F6·F7·F8·F9·F10·F11·F12·F13·F14·F15·F16·F17·F18·F19·F20·F21·F22·F23·F24·F25·F26 계획과 모듈형 모놀리스 전환 노트를 따른다.

## CI 전체 기준과 F25 축소 비교

일반 실행은 `full`이며 기존 필수 검사 이름 `Backend app CI result`를 유지한다. 공통 `package` 작업에서 일반·구조 검사와 실행 JAR 빌드를 한 번 수행하고, 현재 workflow run의 같은 artifact와 SHA-256을 각 격리 검증 작업이 사용한다. ZIP 생성 시각이 다른 별도 빌드를 같은 바이너리로 간주하지 않는다. 원문·PG 비교를 진행한 뒤와 최종 정리 뒤에도 producer의 JAR 해시를 확인한다. 단위·구조 XML은 package 산출물에, 실제 PG/원문 XML은 각 모드 산출물에 있으므로 전체 집계 시 합치되 중복 계산하지 않는다.

첫 실험의 `f25-six-smoke-pilot`은 같은 저장소의 `ci/backend-contract-regression-scope` PR, 기준 `0c1579a8ddb93cdea376be1ea57ac3b5c98bddf3`, 명시된 CI/README 다섯 경로만 바뀐 경우에만 full과 함께 추가된다. F16·F17·F19·F21·F22·F23 JAR 단계만 생략 후보이며, F3~F15·F18·F20·F24·F25의 17개 JAR 단계와 모든 기존 준비·원문/PG·인가·TX·정리 검증은 유지한다. 준비 데이터를 남기는 smoke를 단순히 끄지 않는다. 두 모드는 별도 hosted runner와 임시 자원을 사용하며 `fail-fast: false`로 pilot 실패가 full을 취소하지 않는다. 비교를 위해 별도 full을 더 실행하지 않는다.

`backend_contract_profile.py`는 profile·source/head/base/tree·run/attempt·JAR 해시에 묶인 계획과 실제 step outcome/conclusion을 판정한다. 필수 실행 누락, 예정 밖 skip, continue-on-error로 가린 실패, 잘못된 모드/후보, 미지 JAR 단계, 정리 실패는 통과하지 않는다. `contract-plan.txt`와 `contract-execution.txt`만 현재 evidence artifact에 남기며 전체 환경·step outputs·토큰은 저장하지 않는다. 새로운 JAR 계약을 추가할 때는 workflow step ID와 공통 목록/정책 시험을 함께 갱신해야 한다. 전체 결과 gate는 package와 선택한 모든 모드의 성공을 요구한다.

이 첫 실험은 일반 PR의 자동 축소를 활성화하지 않는다. fork·다른 기준·일반 기능/공통 인증/빌드 변경 등은 full로 실행하고, 명시적으로 부적격 pilot을 요청하면 거부한다. 기존 CI 39분18초와 생략 후보 단계 합계 11분04초는 과거 측정이며 실제 절감 시간은 같은 후보의 full/pilot 결과를 읽은 뒤 판단한다. 비교 결과와 일반 적용 여부는 Obsidian의 CI 개선 계획에 기록하며, 앱·운영·비밀값·데이터·복구 자료를 바꾸지 않는다.

F26은 CI 개선 병합 dev `ce5898071c5022c09634c5e68b0c12c9dc66123e`를 인수하며 full 실행 목록에 `jar_f26`을 추가해 총24단계를 감사한다. F25 고정 비교의23/17 기록은 당시 결과다. 새 기준과 기능 변경은 그 pilot 조건에 해당하지 않으므로 F26은 full만 실행한다. 공통 package/XML 분리와45분 check 상한, 기존 F26 검증을 유지한다.


## F27·F28 본인 메일함 상태 관리의 CI 연결

즐겨찾기 변경과 메일함 삭제는 한 사용자 결과로 함께 검토하되 각각의 명시적인 타입·SQL·원문 비교·실패 검증을 보존한다. F29 발송은 이 범위에 포함하지 않는다. 선행 F26의 공통 package/선택 정책/동일 JAR 해시·실행 감사·always 소유 정리를 그대로 두고 F27 → F28 순서만 추가한다. `jar_f3`부터 `jar_f28`까지26개가 full 필수이며 마지막 owned cleanup을 합쳐27개 결과를 감사한다. F25 고정 pilot의 ref/base/변경경로/6개 skip 조건은 넓히지 않으므로 이 기능 변경은 full만 실행한다. 일반적인 pilot 선택 가능성을 새 기능으로 확대하지 않는다. 기존 F26의24단계 기록은 해당 기준의 이력이다.

첫 결합 check 상한은55분이다. F26 actual full39분58초와 이전42분20초에 새 두 단계의 비용·정리 여유를 고려한 제한이며, F27·F28 실제 시간이나55분 충분성을 보장하지 않는다. 단위별 검증·full 필수 목록·정리·발행 차단은 유지하고 첫 hosted 결과 뒤 상한을 재평가한다. 아래 원문/MVC·로컬 시험 기록은 기존 private 준비 근거다. 이 연결 사본은 고정 F26 `efe0309e4f340d364241138ab54ca3653be641e8` 입력 위의 준비일 뿐이며 실제 feature branch/base·hosted PG/JAR/CI 결과는 부모 통합 뒤 별도 확인한다.

## F27 본인 쪽지 즐겨찾기 값 변경 준비

기존 `PUT /api/v1/notes/{id}/favorite`를 요청한 boolean 값으로 설정하고 commit 뒤 본인 SSE resync와 기존 단건/목록의 favorite·favorites 필터를 연결한다. INBOX/SENT 모두 허용하며 동일 값 반복도 UPDATE·commit·resync를 수행한다. TX15 → member INSERT/lock → current active → find(clock1) → note lock → find(clock2) → favorite UPDATE → hook 등록/commit 순서를 보존한다. 시각은2회로 읽음의 세 번째 Clock 호출을 복제하지 않는다. read_at·unread·상대 mailbox·created_at은 바꾸지 않으며 counterpart/block 조회0이다. 영향 행수0로 새404를 만들지 않고 두 ownership 조회의 엄격한 만료 경계가 부재를 판단한다.

Community의 명시적인 app policy/좁은 port/JDBC/scoped REST 계약과 Boot의 exact PUT matcher·기존 writeJdbc/writerTM/privateTT15/readClock/NoteChangeHints를 조립한다. 새 User API·pool/TM/Clock·SSE registry·범용 mutate 프레임워크는 없다. 원문에서처럼 hook 안 실제 hint Runtime만 타입명 로그 뒤 성공 처리하고 SQL·등록·commit 오류는 전파한다. 인증은 binder보다 먼저이며 기존 일반 REST의 exp 선택 정책·ADMIN/default deny를 유지한다. primitive boolean body의 생략/null/잘못된 JSON 등은 원문 actual MVC 관측 후 비교 provider를 확정한다.

F26 전체 검증 뒤 `f27_ci.py prepare`가 기존 mailbox writer에 `UPDATE(favorite)` 한 열만 추가한다. reader와 F26 writer SELECT/잠금/read_at·기존 blocks 권한은 유지하고 새 DDL/표/index/schema/sequence는0이다. 권한 회수 복구는 marker-only 선행/current exact receipt로 대상을 확인하고 GRANT 뒤 strict를 실행한다. nickname이 회수된 동안 User ACL을 검사하는 이전 strict를 호출하지 않는다.

고정 f06 원문 full20·raw shared13(기존12+원문 PrivateNoteStream)/F18 adapted advice1을 재사용하고 F27 service/controller/advice wrapper3만 생성한다. 동일 원문 stream FQN을 다시 생성하거나 production JAR에 포함하지 않는다. UTC exact PG/MVC 두 클래스만 새 task로 실행하며 boolean 쓰기를 위해 새 Seoul 전조합을 추가하지 않는다. 원문 대표8입력을 실제 관측한 complete0600 JSON(4845bytes/SHA eff87b02c348c147c1acee6ec02aa905d41333f66724ac4813b961dfc9c582df)에서만 provider8행×15필드를 채웠고 비작성 서브가120값을 독립 대조했다. formal MVC16이 로컬5초/1XML 실패0으로 통과했다. PG8메서드15예상·새Seoul0과 JAR18요청·본인resync6/타인0·favorite변경5는 정적 전수 검수한 정확 기대값이며 실제 hosted PG/JAR 성공이 아니다. 미측정/틀린 XML/count/receipt는 fail-closed다. 준비 사본의 일반1465/112XML·구조71/2XML=1536건과 check/bootJar/compatibility compile37초는 로컬 성공이며 실제 PG·JAR HTTP·future feature head의 CI·운영 증거가 아니다.

합성 JAR probe는 본인/다른 회원의 SSE와 실제 발급기 F27_CALLER/OTHER600초 토큰을 사용해 true·동일값 반복·false·SENT·권한 실패/회복·조회 projection/필터를 검증하도록 준비한다. 승인 delta는 본인 favorite boolean 한 셀이고 read_at/unread는 불변이다. 본인 resync와 타인0,16표/S3·소유 seed/FK 정리, 열린 SSE2의 server EOF·registry 종료·두 pool/conn0를 모두 요구한다. raw token/비밀값/개인 본문은 산출물이나 로그에 남기지 않는다. 실제 실행 전 mock/정적 카운터를 성공으로 세지 않는다.

workflow는 선행 F26의 fixture/원문/UTC+thinSeoul/JAR 완료 뒤 F27 무자원 guard → 최소 GRANT → UTC PG/MVC → exact XML → JAR 순서다. always owned cleanup/실패 증거/contents read/발행 차단은 유지한다. artifact에는 f27-results.json만 추가하고 token 또는 모든 JSON glob을 넣지 않는다. 실제 기능 브랜치는 F26 검수·CI·dev 병합 뒤 fresh base/input bytes를 확인하고 만든다.

## F28 본인 쪽지 메일함 삭제 준비

기존 DELETE `/api/v1/notes/{id}`는 TX15/member INSERT·잠금/current active/find(clock1)/note 잠금/find(clock2) 뒤 본인 mailbox를 지우고, 남은 mailbox가 없을 때만 원본 note를 지운다. 상대 mailbox가 있으면 원본과 상대 row를 보존하고, 마지막 mailbox 삭제 때 원문의 FK로 request.note_id와 자식 note.reply_to가 NULL이 된다. 반복 삭제는404/hint0이며 성공은빈200/no-store/noCookie, afterCommit 실제 hints Runtime만 타입 로그 후 성공을 유지한다. 상대 상태·차단 조회·새Clock/트랜잭션·범용 mutate 추출은 추가하지 않는다.

새 exact DELETE matcher는 원문처럼 `/notes/blocks`·`/notes/notifications`도 인증 뒤 UUID400으로 보낸다. 예약어403 예외를 새로 만들지 않고 옛 negative4곳은 충돌 없는 깊은 미편입403 경로로 인계한다. 기존 ADMIN/REST/JWT·read/favorite/stream과 operation 전용TT15/두pool을 유지한다.

선행 fixture는 requests가 없는16표이므로 F27 전체 검증 뒤 F28 synthetic에서만 고정 V6 requests5열/PK·2FK·rate index1과 V7 partial index2를 원문 LF fragment4에서 qualification만 해 추가한다. 이 표의 앱 reader/writer 권한은0이고 기존 mailbox/note DELETE 두 권한만 추가한다. 운영 Flyway/DDL/ACL은 변경하지 않는다. 단계 이전16표와 이후17표를 구분하며 old strict를 확장 뒤 다시 호출하거나 약화하지 않는다. identity·선행/current exact receipt 확인 뒤 ACL을 복구하고 마지막에만 full strict를 확인한다.

private source에서 Core/Boot9+9경로를 입력 지문과 대조해 통합했고 좁은8초/전체37초 검증이 통과했다. 일반1501/116XML+구조71/2XML=1572건 실패·오류·skip0과 JAR/호환컴파일은 로컬 결과이며 실제 PG/FK/소켓 성공을 뜻하지 않는다. 새 runtime18guard도 모의 제어 시험만 통과했다. 실제 selected 원문 observer4를 complete0600 JSON(SHA 342a22bc5c7578ab814bfa9134ed53d8edde710659deeaadd8d04d8b55b99521)에 보존하고 provider4행×14필드를 채운 formal MVC11/1XML도 로컬 통과했다. observer4는 정식 합계에서 제외한다. PG17은 검수한 provider 기대값이고 실제 PG/FK/JAR는 아직 미실행이다. UTC 실제 PG·전체 guard/실제 JAR 및 동일 head CI는 별도 gate다. 두 변경은 본인 메일함 상태 관리 한 PR로 묶으며, 실제 브랜치는 F26 검수·필수 CI·dev 통합 뒤 fresh base에서 만든다.

JAR 계획은 정상 actual issuer600초 F28_CALLER/OTHER, 직전TTL60·소유0600/기존키 보존, 본인/other SSE두개를 사용한다. A의본인INBOX만삭제하고 otherSENT/원본을보존, B의마지막SENT삭제로 own request.note_id와자식C.reply_to NULL전이, C삭제 뒤 알림empty를 확인하도록 준비한다. 각DELETE권한·User실패/복구·반복404·인증/깊은경로를 포함하는정적18HTTP=DELETE9/GET9이며 아직actual요청수가아니다. 승인delta는mailbox3/note2/requestNULL1/replyNULL1뿐이고 read_at/favorite/다른17표/S3는보존한다. ACK seed외참조는삭제하지않으며응답실패뒤commit재관측/모든stream·child정리와최종17baseline복구를요구한다. 열린SSE2 serverEOF/registry성공1/두pool·연결0/비밀로그0 뒤에만 passed다.

workflow는 F27 전체 → 무자원 F28 guards → 원문 requests1표/index3/DELETE2 atomic stage → UTC 정확2클래스 → XML/source receipt → JAR 순서다. 새Seoul0, 항상소유정리·실패증거·발행차단·특정 f28-results.json만보존한다. 첫 결합 상한55분은 위 실제 부모 시간에 근거한 여유이며 F28 실제 소요시간은 hosted 결과에서 확인한다.


## F29 본인 쪽지 발송의 CI 연결 준비

기존 POST `/api/v1/notes`의 입력6필드·strip/hash·멱등 요청키·분/Seoul일 한도·답장·POST/REPORT 문맥을 보존한다. TX15의 정렬 회원 잠금과 현재/수신자 User 조회, 기존 요청의 gone/conflict/replay, 양방향 차단과 한도/문맥 검사 뒤 새 UUID1개로 note1/mailbox2/request1을 저장한다. commit 뒤 수신자에게만 기존 알림7필드 note 이벤트를 전달하고, 같은 키 재전송은 같은 receipt를 반환하며 새 행·알림을 만들지 않는다. 기존 조회·resync·registry 예산을 바꾸지 않으며 발송 SAM만 같은 registry에 연결한다. F27·F28 메일함 상태 관리와 별도 기능 계약이다.

선행 F28 전체 성공 뒤 F29 fixture가 마지막17표 strict/snapshot을 확인한다. 고정 f06의 V4/V5 report 원문 조각으로 synthetic report 표1/identity/index/check만 추가해18표를 비교하며 운영 DDL·migration·reader report ACL·앱 sequence ACL은 변경하지 않는다. writer 추가는 notes의 sender SELECT/발송9열 INSERT, mailbox5열 INSERT, requests5열 SELECT/INSERT, reports의 report_id/author_id/deleted_at SELECT로 제한한다. 권한 회수 중에는 marker/prior/current exact receipt로 소유 대상을 먼저 확인하고 GRANT 복원 뒤에만 새 full strict를 실행한다. 확장 후 이전17표 strict를 재호출해 완화하지 않는다.

원문 고정 source full22·raw shared13+adapted advice1·generated3과 기존 `prepare_f29_legacy_oracle.py`를 재사용한다. workflow는 source object 확인/hosted 거부, 무자원 fixture24+runtime13 guard, prepare, `noteSendCompatibilityTest` UTC, `noteSendCompatibilitySeoulTest`의 한 시각 메서드, results, 필수 `jar_f29` 순서다. source/호환 코드가 executable JAR에 들어가지 않도록 F29 wrapper3을 기존 제외식에 더한다. 새 selector나 검증 모드는 없고 모든 `jar_f3..jar_f29`27개 및 owned cleanup까지28개 결과를 같은 source/head/base/tree/run/JAR에 묶어 감사한다. pilot 자격·기존6skip 조건은 넓히지 않으므로 이 기능은 full만 실행한다. package 한 번/동일 JAR·실패 gate·정리·발행 차단은 보존한다.

실제 부모 PR183의55분 check 상한과 F26 full39분58초/이전42분20초에 근거한 여유 판단을 그대로 보존한다. F29까지의 실제 hosted 총시간이나55분 충분성을 보장하지 않는다. 개별 helper 예산·정확 결과 판정·항상 소유 정리 조건을 완화하지 않는다.

원문 observer6과 formal MVC12, fixture24/runtime13 순수 모의37의 기존 로컬 성공은 재사용하는 근거다. PG26·Seoul 지정1은 소스/provider 기대값이며 실제 PG 성공이 아니고 JAR15HTTP=POST9/GET6·수신 note3/송신 note와 resync0·신규 note3/mailbox6/request3은 검산한 계획이다. 초기 private prefix의 REPORTS/SEOUL_METHOD=None은 준비 이력이다. 현재는 root가 source/provider를 검산해 UTC PG26(소스 기대)/MVC12(기존 로컬 실측), Seoul PG1(소스 기대)과 정확한 시각 메서드를 고정했다. 관련 측정 gate 모의4건은 root 인계상0.170초 통과했으며 실제 PG/JAR 성공은 아니다. results는 이후 실제 생성된 exact XML을 이 기대값·원문 receipt와 대조해야 하고, smoke도 실제 전달·상태·자원 종료를 별도로 증명해야 한다. 동적 UUID는 응답/DB/SSE 일치 후에만 ACK하고 응답 유실 commit은 exact request 소유 행으로만 정리하며, raw Instant와 DBµs는 문자열 동일 대신 최대1µs 정밀도 의미를 비교한다. 실제 PG/JAR/socket·CI 성공으로 확대하지 않는다.

실제 F29 기준은 F26 post-CI와 결합 F27/F28의 검수·필수 CI/dev 통합 뒤 fresh base로 확인한다. 이 private 연결은 미래 branch/base나 게시를 확정하지 않는다. artifact에는 기존 txt/log와 특정 `f29-results.json`만 추가하며 토큰·전체 환경·전체 step outputs는 보존하지 않는다.


### 쪽지 만료 정리 후보

`pawbridge.jobs.cleanup-enabled=true`에서만 기존 writer/TM/Clock으로 쪽지 만료 정리를 등록한다.
기본값과 false는 off이며 기존 Community의 공통 정리 설정을 끄거나 운영 실행 소유권을 이전하지 않는다.
최초 지연과 이전 완료 후 지연은 각각 5분이다. 각 SQL의 삭제 키를 `MATERIALIZED` CTE로 한 번만 선택해 최대300건을 처리한다.
실제 원문 PG 비교에서301개 쪽지가 삭제된 결함을 확인했으므로, 원문 SQL 자체의 동등성 대신 의도한300건 상한을 보완한 차이다.
원문 pin/SQL은 비교용으로 보존하고 그 사례의 관측을 상한 검증 성공으로 세지 않는다. 후보는 정확한 oldest300·잔류1·18표 전체 변경 범위를 별도로 검증한다.
조건부 ordinary scheduler는 새 예약을 막고 기존 phase 10초 대기 뒤 destroy의 지역 대기 5초를 사용한다.
이는 전체 15초 종료나 JDBC interrupt 보장이 아니다. TX 잔여 timeout은 JDBC query timeout보다 우선할 수 있다.
CI는 기존 F29 원문/JpaTM·18표 소유 fixture를 재사용하여 UTC expiry tag 6사례를 별도 선택한다.
동일 클래스의 기존 F29 26사례와 Seoul 1사례에서는 expiry tag를 제외한다.
실제 활성 작업의 FK 잠금→context close→정상 잠금 해제→commit/연결 반환→pool 종료를 확인한다.
이 증거는 실행 JAR의 강제 취소나 운영 예약 인수 완료를 뜻하지 않는다. 새 HTTP/JAR 여정은 복제하지 않는다.


## 병합 후 동일 코드의 전체 검증 인수

PR에서는 package와 선택된 계약 검증을 실행한다. 미분류 변경의 기본값은 full이다. dev push의 `proof` job은 새 package보다 먼저, 방금 병합한 같은 저장소 PR의 **검사된 전체 Git tree**와 현재 dev가 같은지 판정한다. 첫 범위는 push 직전 base와 head를 두 부모로 가진 단일 merge commit이며 squash/rebase·여러 PR·다른 tree는 현재 선택된 계약을 새로 실행한다. 브랜치명이나 PR head SHA만 같다고 재사용하지 않는다.

`backend_postmerge_proof.py`는 실제 완료된 원본 backend-app run/attempt의 package·full·최종 결과, 현재 workflow에 대응하는 실제 성공 단계, 원본 full plan/실행/cleanup 증거, producer와 consumer의 JAR SHA·source/tree·workflow 정체성을 연결한다. 27개 JAR receipt만으로 전체 PG 등 다른 단계의 성공을 대신하지 않는다. 원본 성공 후 24시간을 넘거나 자료가 없고 만료·모순·조회 실패가 있으면 현재 선택된 검증을 새로 실행한다. 원본 PR에서 실제 사용한 workflow와 현재 dev를 비교하므로 PR 자체의 CI 코드 변경을 금지하는 규칙은 아니다.

통과한 dev 실행은 별도 `backend-app-postmerge-proof-<run>-<attempt>`의 `postmerge-proof.txt`에 `reused-full`과 원본 실행을 기록하고 새 package/full은 실행하지 않는다. 과거 XML·fixture·토큰·receipt를 새 시험 결과로 복제하거나 JAR을 다시 빌드해 과거 SHA와 비교하지 않는다. 새 merge SHA와 검사 당시 synthetic SHA는 다를 수 있으며 전체 tree로 결속한다. 다른 필수 CI와 이미지 발행 차단은 유지한다.

최종 `Backend app CI result`는 선택·판정이 성공하고 **execute+package/check 성공**, 또는 **검증된 reused-full+package/check의 예상된 생략**만 허용한다. execute는 현재 선택한 검증을 새로 실행한다는 뜻이며 full coverage를 주장하지 않는다. 판정 실패·빈 mode·예상 밖 생략은 성공이 아니다. 읽기 권한과 현재 실행 토큰은 판정 job에 한정하며 새 secret이나 쓰기 권한을 요구하지 않는다.

이 방식은 같은 코드에 대한 기존 검증을 인수한다. 새 runner/JDK·외부 의존성을 다시 시험했다는 의미가 아니며 원본 환경·검증 시각을 보존한다. 실제 제거한 중복 실행과 병합 후 소요를 측정하고 PR 자체의 full 소요 또는 전체 개발시간 단축과 구분한다. 재사용에 문제가 있으면 CI 수정/revert로 기존 full 경로로 돌아가며 앱·DB·배포를 되돌리지 않는다.

판정과 최종 gate는 `.github/scripts/test_backend_postmerge_proof.py`와 `test_backend_app_ci_policy.py`에서 합성 Git/API 응답·임시 파일·실제 gate 실행으로 검증한다. 실제 hosted full·병합 후 재사용 성공은 해당 실행 증거가 있어야 선언한다.

## 쪽지 만료 계약의 선택 검증

`note-expiry`는 실제 전체 검증을 통과한 `a35ee31ba700093c97fa9c0864d6a8677e5fae78`의 애플리케이션·테스트·Gradle·리소스·준비 도구 입력을 고정한다. README와 별도 검수한 F4 두 파일만 달라질 수 있으며 F4 두 파일은 내용 해시도 고정한다. 현재 이벤트의 변경 경로도 F30와 연결 CI 범위에 속해야 한다. 다른 앱·인증·트랜잭션·시험·도구 변경이나 입력 확인 실패는 full로 돌아간다. 브랜치 이름만으로 생략하지 않는다.

전체 일반 단위·구조 검사와 실행 JAR 빌드는 유지한다. 기존 schema/소유권/최소 ACL/원문 지문 준비를 같은 순서로 수행하고 F4의 두 FK 보조표만 원래 위치에서 준비한다. 과거 JAR27 및 관계없는 PG 여정은 명시적으로 미실행이다. 새 소유 fixture에서 F30의 정확한 PG6과 실제 활성 scheduler의 트랜잭션·정상 종료 계약을 다시 검증하고 항상 소유 정리를 실행한다. 준비된 과거 업무 행 이력이 full과 같다는 주장은 하지 않는다.

`contract-plan.txt`와 `contract-execution.txt`는 `profile=note-expiry`, 미실행 JAR27, 필수 F4 shape/PG/cleanup의 실제 결과를 기록한다. PG XML은 정확한 클래스·6메서드·실행 개수와 숨은 실패/오류/skip/disabled까지 검사한다. 이 scoped 성공은 full 성공이나 full 증거 재사용으로 승격하지 않는다. dev 병합 뒤에도 scoped 경로는 새로 실행한다. 소요 시간은 실제 hosted 실행 후에만 보고한다.


## 관리자 회원 삭제와 Community 정리

기존 ADMIN DELETE `/api/v1/admin/users/{userId}`를 User의 pending 커밋 → TX 밖 Community 공개 호출 → User의 별도 최종 TX로 연결한다. Boot가 콜백을 주입하며 User에서 Community 모듈을 역참조하지 않는다. 기존 두 pool과 writer TM을 재사용하고 각 작업의 TT15 및 기존 관리자 JWT 인가를 유지한다.

Community 정리는 쪽지 member 잠금과 pending 재검증 뒤 본인 mailbox·차단·발신 요청의 FK 효과, 원본 context/요청 hash 익명화, 영향받은 고아 note 삭제를 수행한다. 설치된 채팅 표의 membership·발신 요청 삭제와 메시지/room 회원 ID 익명화도 같은 TX에서 처리한다. 실패하면 이 Community TX 전체를 원복한다. 커밋 후에만 같은 쪽지 registry의 본인 연결을 닫고 나머지 연결에 resync한다.

첫 인계는 명시적 `pawbridge.chat.enabled=false` 모드다. 미지정 시 기존 `MEMBER_CHAT_ENABLED` 별칭과 false 기본값을 따르며 canonical 속성이 우선한다. false라도 설치된 채팅 데이터 정리는 생략하지 않는다. 후속 회원 채팅 편입에서는 true의 Redis WITHDRAWN 생산·소비와 연결 종료 배선도 조립했다. 실제 Redis·WebSocket과 회원 삭제 연계의 통합 검증 전까지 운영 삭제 인계 완료를 뜻하지 않는다.

Community 실패는 pending을 보존하고503을 반환한다. Community가 커밋된 뒤 User 최종 TX가 실패하면500이며 pending 상태에서 권한 있는 재시도로 마무리한다. 최종 User 조회 시 이미 부재하면 원문 deleteById처럼 성공하고, 조회 후 동시 삭제로 DELETE가0행이면 optimistic 실패로 최종 TX를 원복한다. 모든 단계를 감싸는 새 outer TX나 추가 행 잠금은 없다.

실제 원문 JpaTM/후보 JdbcTM 비교는 기존 F29/F30가 완료된 뒤에만 원문 User2표·설치된 chat4표와 필요한 열 권한을 합성 fixture에 더한다. 원래18표 단계의 strict를24표 단계에 다시 적용하거나 완화하지 않는다. 고정 원문40/shared22/generated6의 별도 receipt를 DDL 전에 확인하며, 원래 F29 원문22/generated3 receipt와 발송 여정은 보존한다.

후기 `memberCleanupCompatibilityTest`는 업무 비교11사례와 마지막 실제 issuer 토큰 준비1사례의 정확한 XML12를 요구한다. 이어 같은 실행 JAR의 `member-cleanup-smoke`가 관리자 삭제의 Community rollback503 → User rollback500 → 권한 있는 retry200, 본인 SSE 종료·생존자 resync와 원본 본문 보존을 확인한다. JWT 없는401·위조 ADMIN 거부403·pending의 정상 JWT 재연결403도 구분한다. 실제 소켓·PG 성공은 해당 hosted 결과로만 판정한다.

새 필수 `jar_f31`은 기존 `jar_f3..jar_f29`27개와 별개로 계약 plan/audit에 포함한다. 항상 실행하는 자기 fixture cleanup까지 성공해야 하며, 원문 백업·운영·Windows·R2에 접근하지 않는다. 이 문단은 실제 CI나 운영 성공을 선언하는 기록이 아니다.

후기 회원 삭제의 원시 자식 로그는 처음부터 소유 RUNNER_TEMP/0700 폴더와0600 파일에만 기록한다. 모든 종료 경로에서 제한 크기·비밀값을 검사하며, 두 자식의 정상 정지와 검사 성공이 확인될 때만 업로드 경로에 사본을 만든다. 실패·정지 미확인·민감값 감지 시 원시 로그 사본은0이고 incomplete 결과만 보존한다.


## 관리자 회원 상세 조회

ADMIN GET `/api/v1/admin/users/{userId}`는 기존 `UserProfileQuery`와 같은 read-only 조회 bean을 소비한다. 기존 회원7필드와 성공200/부재400/예외500 응답을 재사용하고 새 SQL·열 권한·pool·TM·schema를 추가하지 않는다. pending 행을 임의로 제외하거나 비밀번호 등 다른 필드를 반환하지 않는다.

서명된 ADMIN 권한을 경로 바인딩/조회보다 먼저 확인하며 X-User 헤더나 query의 userId로 대상을 바꾸지 않는다. 아직 편입하지 않은 `/users/search`는 이 단건 matcher에서 제외하고 목록·검색·미편입 이웃 경로의 기본 거부를 유지한다. 기존 관리자 DELETE는 그대로다.

이 변화의 가장 가까운 보호는 실제 MVC의 JWT·대상 바인딩·7필드/오류·인접 경로와 명시 조립 시험이다. 기존 F12의 변경 없는7열 SQL/실제 PG 근거를 재사용하고 API마다 별도 PG fixture·JAR 여정을 복제하지 않는다. 새 기능의 실제 Git 기준/hosted CI/병합은 해당 PR 실행 근거와 구분한다.

### 회원 관리 계약 선택 검증

`member-management`는 검수한 회원관리 앱·시험·준비 코드의 고정 SHA가 일치하는 같은 저장소 PR/dev push에만 선택한다. 공통 모듈 빌드·단위/구조 검사와 동일 JAR을 유지하고, F4 보조표 준비 → 기존 쪽지 만료 PG6 → 회원 fixture → 회원 PG12 → 관리자 삭제 HTTP9/SSE/실패 후 재시도 → 소유 정리를 실행한다. 관계없는 과거 JAR27과 PG 회귀는 `skipped`로 남기며 이 결과를 전체 회귀나 full 재사용 증거로 승격하지 않는다. 고정 범위를 벗어나면 기존 full 경로로 돌아간다.

## 관리자 가입통계

ADMIN GET `/api/v1/admin/users/stats/daily-signups?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD`는 nullable UTC 저장 `created_at`에 9시간을 더한 날짜를 양끝 포함 집계하고 정렬한다. 빈 날짜를 채우지 않으며 역순 구간은 원문처럼 빈 결과다. GET `/api/v1/admin/users/stats/signup-periods`는 Seoul 오늘을 한 번 구하고 한 read-only 트랜잭션 안에서 오늘·최근7일·최근30일·이번달을 네 번 조회한 뒤 각 구간의 빠진 날을0으로 채운다. 네 결과가 하나의 snapshot이라고 가정하지 않는다.

기존 reader/Clock/열 권한·응답 envelope·ADMIN 선행 인가를 재사용하며 새 schema/권한/pool은 없다. 필수 날짜 누락400과 원문의 날짜 변환 Runtime500을 구분한다. 기존 F19 JAR의 음성 경로는 이제 미편입 `/api/v1/admin/users/stats/daily-signups/extra`로 옮기고6요청·역할·복구·DB/S3 불변 검증은 유지한다.

private 앱 준비에서 단위·실제 JWT MVC·조립·기존 회원수 회귀5XML52건을 확인했다. 원문/후보 날짜 SQL과 중간 commit 가시성 비교는 기존 F19 자료를 확장한 UTC3·Seoul 경계1의 별도 PG gate이며 새 JAR 여정은 추가하지 않는다. 이 기록은 private 준비 근거다. 실제 feature head의 PG/hosted CI·병합 결과는 해당 run 산출물로 확인하며, local compile이나 모의 journey를 실제 PG/JAR 성공으로 세지 않는다.

## 관리자 회원 수정

ADMIN PUT `/api/v1/admin/users/{userId}`는 기존 writer의 한 TX에서 회원을 `FOR UPDATE`로 읽고, 보호소 승인 필요 조건 → 닉네임 중복 → 변경 순서를 유지한다. 관리자 전용 nullable 닉네임 Pattern(2~10자 영문·숫자·한글), 세 역할과 careRegNo의 원문 제약을 보존한다. 같은 raw 닉네임은 중복 조회를 하지 않고, 실제 dirty가 있을 때만 감사시각과 UPDATE를 수행한다. 선행 중복은409, SQL unique 경합과 commit 실패는500이며 새로운 retry나 역할 승격 정책을 추가하지 않는다. 기존 writer/TM·TT15를 재사용하고 무제한 대기까지 원문과 같다고 주장하지 않는다.

`admin-member-update` 선택 검증은 고정 앱20 SHA와 공통 빌드·단위 검사를 유지한다. 원문 컴파일 → 회원 합성 fixture24 → 후기 최소4열 권한 준비 → 원문 JPA/후보 JDBC의 UTC16 고유 사례 → 소유 정리를 요구하며 과거 JAR28·무관 PG를 반복하지 않는다. 운영 권한·DDL·회원 이력은 변경하지 않는다. 실제 source PG/잠금/감사시각/원복 성공은 해당 CI 결과로만 판정한다.


## 방문 식별·집계와 관리자 기간 통계

`pawbridge.visits.collection-enabled`는 기본 false다. 기존 `pawbridge.visits.enabled`가 true인 단독 조회는 서명키나 쓰기 lifecycle을 요구하지 않는다. collection=true는 read=true와 유효한 `pawbridge.visits.signing-key`를 요구하며 모순된 설정은 기동에 실패한다.

활성화하면 공개 POST `/api/v1/visits/identity`, `/api/v1/visits/today`와 ADMIN GET `/api/v1/admin/visits/stats/period`를 조립한다. Origin·bot·중복 cookie 검사, host cookie/HMAC·서울 일자 구분과 원본 204/409 응답을 유지한다. 집계는 기존 writer/TM의 10초 트랜잭션으로 날짜별 공유 잠금 후 날짜를 다시 확인하고 식별자 중복 제거와 일별 합계를 함께 반영한다. 기간 조회는 수집 시작 이전 날짜를 만들지 않고 수집된 날짜의 빈 합계만0으로 채운다.

단일 Spring lifecycle이 준비 완료 때 수집 시작을 기록하고 60초 간격으로 정리한다. 서울00:05 이전에는 지우지 않으며 날짜별 배타 잠금·개별 커밋으로 오래된 식별자만 제거한다. 일별 합계와 수집 시작 시각은 보존한다. 기존 writer/Clock을 재사용하고 scheduler는 후보 내부에만 조립한다. 운영의 예약 작업을 인계하거나 실행한 변경이 아니다.

고정된 앱22개와 README 경로만 허용하는 `visit-collection` CI는 공통 Java21 빌드·단위 검사, 원문 준비, member24→방문26표의 최소 fixture, 실제 UTC9·Seoul1 비교와 소유 정리를 요구한다. selected는 기존 관리자 추가 권한을 받지 않으며 full은 명시 admin 모드를 사용한다. 과거28 JAR 여정은 selected에서 미실행이고 전체 회귀 성공으로 승격하지 않는다. 현재 원문·후보 준비와 관련 단위/HTTP 검증은 통과했으며 실제 PostgreSQL10사례와 실행중 정리·종료 검증은 CI 결과로 별도 판단한다.

## refresh 회전과 logout

`pawbridge.auth.sessions-enabled`는 기본 false다. true일 때만 공개 POST `/api/v1/auth/refresh`와 인증이 필요한 POST `/api/v1/auth/logout`을 조립한다. 기존 `PostWriteSettings`의 같은 키·writer·트랜잭션 관리자를 사용하며 외부 `X-User-*` 값은 인증 근거로 쓰지 않는다. 두 TTL 설정 `jwt.access-token-expiration`, `jwt.refresh-token-expiration`은 원문과 같은 밀리초 단위로 명시 입력하며 새 운영 기본값을 만들지 않는다. false인 기존 기동은 TTL을 요구하지 않는다.

refresh는 body의 refreshToken 검증→저장 행 조회→DB 만료 시각의 strict 비교→현재 회원 필드 조회→두 토큰 발급→선택한 PK의 token/expiry 갱신 순서로 한 writer 트랜잭션에서 처리한다. 원문의 createdAt·밀리초에서 초로 내림한 DB 만료·현재 회원 claim을 보존한다. 이미 만료된 행을 삭제한 뒤 Runtime 예외로 롤백하는 원문은 실제 SQL DELETE 전에 롤백되므로 후보는 이를 물리 삭제로 바꾸지 않는다. 실제 비교 결과는 PG gate에서 확인한다.

logout은 인증된 사용자에게 속한 refresh 행을 먼저 선택하고 해당 PK만 지운다. 선택 뒤 별도 트랜잭션에서 추가된 행까지 일괄 삭제하지 않는다. 원문처럼 이미 발급된 access token을 별도 차단 목록에 넣지 않으며, CAS·version·nonce·새 계정 연결 정책도 추가하지 않는다. 동시에 같은 refresh를 읽은 두 요청이 모두 성공할 수 있고 최종 저장값은 마지막 갱신 결과일 수 있다. 후보 TT15는 원문 무제한 대기와 같다고 주장하지 않는다.

고정 앱23개에 한정한 `auth-sessions` CI는 공통 빌드·단위 검사, 기존 member24와 명시 admin24 준비, 새7컬럼 권한의 합성 확장, 원문 실제 JPA와 UTC17·Seoul 정상회전1 비교를 요구한다. 실패한 경우에도 새7권한만 회수하고 원래 admin strict를 검증하며, 원래 PG 실패와 권한 복구 실패를 각각 감사한다. full은 권한 복구 뒤 방문26표를 준비한다. 새 테이블·seed 생성 JAR·별도 source set·원문 fetch는 없다. 실제 PostgreSQL과 hosted CI는 게시 후 판단하며 운영 키·TTL이나 현재 로그인 정책은 변경하지 않았다.

## 관리자 게시글 기간·오늘 통계

ADMIN GET `/api/v1/admin/posts/stats/period`와 `/api/v1/admin/posts/stats/today`는 기존 reader와 Boot의 명시적 조립을 재사용한다. 기간 조회는 필수 ISO 날짜·최대366일·서울 날짜 경계와 삭제 제외를 보존하고, 이전 날 비교와 두 집계를 같은 읽기 전용 REPEATABLE_READ 트랜잭션에서 수행한다. 일자별 빈 구간을 새로 채우거나 게시판 결과의 정렬 순서를 만들지 않는다. 오늘 조회는 원문의 JVM 기본 날짜 경계를 사용하며 기간 조회와 같은 기준이라고 단정하지 않는다. 새 풀·스키마·운영 권한은 추가하지 않는다.

고정 앱16개와 README 경로만 허용하는 `admin-post-statistics` CI는 공통 Java21 빌드·단위 검사, F2/F3/F4/F5/F7의 공통 fixture·권한 준비, 원문 컴파일, UTC4·Seoul1 및 소유 정리를 요구한다. 이 읽기 시험이 사용하지 않는 F8~F29의 DB 준비22단계와 과거28 JAR 여정은 명시적으로 생략하고 raw 실행·생략 결과를 감사한다. 원문 준비와 기존 full의 실행 내용은 유지하며 선택 결과를 full로 승격하지 않는다. 로컬 단위·HTTP·구조와 source 컴파일 성공은 실제 PostgreSQL 비교 성공과 구분한다. 운영 변경이나 배포를 수행하는 구성이 아니다.

## 관리자 게시글 조회·수정·삭제

ADMIN GET `/api/v1/admin/posts`, GET/PUT/DELETE `/api/v1/admin/posts/{postId}`를 한 관리 흐름으로 조립한다. 목록은 원문의 Pageable/PageImpl 계약을 같은 Spring Data Commons 3.4.11로 재사용하고 application에는 순수 페이지 계약만 둔다. 기본20·최대2000·fallback·다중 정렬·전체 건수와 partial-page count 생략, 삭제 제외·9필드·이미지 JSON·닉네임을 보존한다. 단건은 기존 ReadPost를 재사용한다. 원문의 `/read`, `/search` 같은 비숫자 단건 값도 매핑에 포함되며 별도 새 API나 예외 경로를 만들지 않는다.

관리자 PUT/DELETE는 명시적인 AsAdmin 진입점으로 작성자 검사를 생략한다. 개인 수정·삭제의 소유권 검사는 유지하며 실제 게시글 작성자 ID를 기존 writer·닉네임·payload에 사용한다. 기존 JDBC/locking SQL·TX·R2·Outbox·검색 정리를 재사용한다. 첨부의 부분 실패를 자동 보상하거나 외부 I/O와 DB를 새 단일 트랜잭션으로 묶지 않는다. 새 업무표·SQL·권한·풀은 추가하지 않는다.

고정 앱32개와 README 경로에 한정한 `admin-post-management` CI는 공통 빌드·단위/HTTP·계층 검사, 기존 원문 준비와 실제 페이지 조회 PG11, 소유 정리를 요구한다. 과거28 JAR 여정과 무관39계약은 명시적으로 생략하며 scoped 결과를 full로 승격하지 않는다. 합집합의 관련65사례·실제계층 검사는 로컬에서 통과했다. 관리자 쓰기 전용 실제 PG 시험은 이번에 추가하지 않았고 변경되지 않은 F4/F5 인프라의 이전 검증을 재사용한다. 새 관리자 쓰기의 실제 DB/R2 통합은 운영 전 별도 확인 사항이며 현재 운영을 변경하거나 배포하는 구성이 아니다.


## 회원 채팅의 REST·실시간 전송 편입

기존 회원 채팅의 업무 메서드와 20개 SQL을 JDBC 구현으로 재사용하고, User의 명시 조회 API와 기존 차단·쪽지·탈퇴 정리에 연결한다. REST는 서명 검증된 사용자와 JWT 만료 시각으로만 본인을 결정한다. 익명 WebSocket handshake 뒤 STOMP CONNECT에서 30초짜리 일회용 Redis ticket을 원자적으로 소비하며, 인증 전 SEND·SUBSCRIBE와 다른 사용자 큐 접근을 거부한다. 기존 Origin·frame·세션 수·만료·중복 전송·본문 보관 정책을 유지한다.

`pawbridge.chat.enabled`는 기본 false다. false에서는 채팅 Redis·소켓·유지관리 scheduler를 만들지 않는다. true일 때 명시 Redis 연결과 namespace를 요구한다. `pawbridge.jobs.cleanup-enabled=false`이면 true 채팅에서도 보관 기한 정리는 실행하지 않으며, 세션 lease 관리는 별도 유지한다. 기존 설치 데이터의 회원 탈퇴 정리는 이 옵션과 별개로 기존 흐름에서 한 번만 처리한다. DB 커밋 뒤 WITHDRAWN 신호로 본인 연결을 닫고 살아 있는 연결에는 재조회 신호를 보낸다.

종료 시 로컬 세션을 먼저 분리해 동기·지연 close callback의 중복 Redis/User I/O를 막고 기존 lease 만료를 사용한다. 이는 모의 callback 검증이며 실제 JVM·소켓 종료 시간의 증거는 아니다. 신규·관련 단위/HTTP·인가·프로토콜·조립과 계층 검사는 수행했으나 실제 Redis Lua/리스너·WebSocket·채팅 DB 통합은 아직 미실행이다. 선택 CI의 관리자 페이지 PG11을 채팅의 통합 검증으로 세지 않는다.


## 기존 Google 로그인 편입

`pawbridge.auth.google-enabled`는 기본 false다. true에서는 기존 AuthSessions·JwtSessionTokens와 명시 Google client/secret/callback·frontend 설정을 요구한다. 정확한 Google authorization/callback 두 경로만 별도 보안 체인으로 조립하며 REST의 STATELESS JWT 경계를 유지한다. OAuth 요청 state는 기존 Spring의 임시 HttpSession 저장 방식을 사용한다. Google 네트워크 호출·자격증명·운영 설정을 이 구현 과정에서 실행하거나 변경하지 않았다.

Google의 이메일/공급자로 기존 회원을 우선 찾고 LOCAL 충돌은 자동 연결하지 않는다. 신규 회원의 기존 닉네임·ROLE_USER·provider ID와 감사시각을 보존한다. 기존 User nickname 저장소를 재사용하며 Google 외부 I/O 후 회원 생성 TX와 refresh 저장 TX를 분리한다. 계정 연결·LOCAL 폐지·Naver는 추가하지 않는다. unique 충돌의 원래1회 추가시도는 실패 TX 종료 뒤 별도 TX로 이동하며, 최초 refresh 저장은 같은 회원 행 잠금 뒤 재조회한다. 실제 JPA flush/23505·동시 가입·DB 감사시각의 동등성은 아직 PostgreSQL 비교로 입증하지 않았다.

성공 redirect는 원문처럼 access/refresh query를 유지한다. 임의 예외/원격 응답을 실패 주소에 복사하지 않고 정해진 문구만 허용한다. redirect URL 전체를 DEBUG로 기록하던 framework 경로는 같은 응답의 servlet redirect로 제한했고, 재현 RED→수정 GREEN으로 토큰 미기록을 확인했다. 이는 실제 HTTP framework TRACE 전체 로그의 검증이 아니며 query 토큰 자체의 전달 방식 개선은 후속 P8 계약이다.

가까운 업무·설정·state/오류·JWT REST·기본 비활성·DEBUG 회귀의 고유30사례는 분할 실행으로 통과했고 계층383class도 검사했다. 실제 Google 인증 교환/브라우저 쿠키·활성 JAR·DB/Redis 통합은 아직 미실행이다. 고정 User 합집합 입력의 선택 CI는 공통 빌드/단위·기존 관리자 페이지 PG11과 cleanup만 실행하며 Google 통합이나 전체 full 성공으로 확대하지 않는다.


## 관리자 회원 목록·검색

ADMIN GET `/api/v1/admin/users`와 `/api/v1/admin/users/search`는 기존 reader·읽기 전용 TX·회원7필드 projection을 재사용한다. 기본20건/createdAt DESC와 Page wire·부분 페이지의 count 생략, 원문의 trim/escape 없는 email/name/nickname LIKE와 role AND 필터를 유지한다. JWT ADMIN 판정을 바인딩·DB 조회보다 먼저 수행하며 외부 사용자 헤더를 신뢰하지 않는다.

정렬은 기존 조회 권한의7필드만 허용한다. 원문 JPA가 받을 수 있는 password/careRegNo/providerId/updatedAt4필드는 후보가 명시 거부한다. 이는 알려진 지원범위 차이이며 완전 호환으로 표시하지 않는다. 해당 정렬을 위해 비밀번호 열 조회나 운영 권한을 늘리지 않았다. 실제 운영 전환 전 정렬 정책을 확정해야 한다.

원문·후보 SQL의 실제 PostgreSQL LIKE/collation·정렬·동시 count/JPA 동등성은 아직 미실행이다. 가까운 JDBC/HTTP/명시 조립의 첫 선택41사례/5XML와 계층390class는 통과했으며 모의 transaction을 실제 PG 롤백으로 표현하지 않는다. 고정 코호트 선택 CI도 Google·목록/검색의 실제 DB 통합 대신 공통 단위·구조와 기존 관리자 게시글 페이지 PG11만 요구한다.


## User HTTP 편입 묶음

Google·관리자 회원 목록/검색·보호소 신청/승인·본인 동물 활동을 개별 대기열 대신 User 모듈의 한 편입 PR로 정리한다. 원문53개 앱/시험 파일의 연속 before/after와 최종 활동 사본 바이트를 전수 대조했으며 업무 코드를 다시 작성하지 않았다. 기존 private 준비와 실패 증거는 보존한다.

보호소는 `pawbridge.shelter-membership.enabled=false`, 동물 활동은 `pawbridge.user-animal-activities.enabled=false`가 기본이다. true에서는 명시 Animal origin을 요구한다. 보호소는 현재 DB 관리자 확인과 User→신청 재잠금, TX 안 Animal 조회, 승인 역할/등록번호·감사시각, 원문의 RR 기간 통계를 유지한다. 찜 추가/제거는 기존 Favorite와 Outbox를 같은 TX에 저장하고 payload eventId와 Outbox event_id를 별도로 생성한다. 삭제0행에서는 이벤트를 만들지 않는다. 본인 동물 조회의 DELETED fallback과 원문 Page wire도 유지한다.

Kafka 보상 소비/manualACK·재시도·processed 저장·정리 예약의 소유권은 아직 편입하지 않았다. LOCAL 가입/로그인·이메일, Store 활동 조회와 P8 계정 정책도 이 묶음에 없다. 이 상태로 구 User 실행기 종료나 Kafka 종료를 완료했다고 표시하지 않는다.

코드 활성화 전에는 선택한 writer의 실제 DML 권한, Google/Animal/Redis/Kafka·DB 통합 및 JPA flush/감사시각/경합을 격리 환경에서 검증해야 한다. CI cleanup 역할을 새 true 기능에 그대로 재사용할 수 있다고 가정하지 않는다. 이 작업은 실제 권한이나 운영 설정을 변경하지 않았다.

선택 CI는 고정108 입력과 공통 빌드·단위/HTTP/구조, 기존 관리자 페이지 PG11·소유 정리를 요구한다.39계약·28JAR의 미실행과 새로운 User SQL/외부 연동의 미실증은 명시적으로 남기며 full 또는 운영 인계 성공으로 확대하지 않는다.

## 실종·목격 제보

`pawbridge.animal-reports.enabled`는 기본 false다. 선택 reader/writer의 실제 제보 조회·DML/행잠금·생성키 권한과 격리 요청을 검증한 뒤에만 명시 true로 활성화한다. false에서는 제보 빈과 보안 matcher를 만들지 않고5경로를 기본 거부한다. 이는 원문과 다른 기동 보호이며 기존 CI 최소계정의 권한을 임의 확장하지 않는다. 기동 성공은 제보 준비 완료가 아니며 이 gate 전에는 외부 라우팅을 인계하지 않는다.

명시 활성화에서 공개 GET `/api/v1/reports`와 `/{reportId}`, JWT 본인 POST/PUT/DELETE를 조립한다. 기존 목록 필터·Page wire·상세22필드·소유권·soft-delete·사진/종류 변경 제한과 닉네임 fallback을 유지한다. reader/writer·TM·R2·User nickname 조회를 재사용한다. 원문 JPA의 int 범위를 넘는 offset은 읽기 TX 안·SQL 전에 같은 예외로 거부하며 공개 조회를 큰 OFFSET SQL로 넘기지 않는다.

사진은 reports/images 경로를 사용하고 부분 업로드 실패는 앞서 올린 객체를 정리한다. INSERT 본문/saveAndFlush 상당 실패는 업로드를 정리하지만 proxy commit 실패는 원문처럼 별도 보상하지 않는다. soft-delete에서 사진을 지우거나 새 Outbox를 만들지 않는다. JPA preflush 응답의 이전 updatedAt을 보존한다. 이런 원문 동작을 새로운 보상 정책으로 대체하지 않았다.

비작성 서브가 생산8/시험4·변경23메서드를 대조했다. 고유49사례는 분할 선택 성공 합집합이고 초과 offset 신규1은 RED→GREEN으로 SQL0을 확인했다. User와 함께 명시 조립을 합친 뒤 새 조립1·466class 계층 검사도 수행했다. 실제 PG 권한/SQL·JPA 감사시각/경합·R2/활성 JAR은 아직 미실행이며 코드 편입만으로 운영 인계를 완료했다고 표시하지 않는다. 고정130 선택 CI의 관리자 페이지 PG11은 제보 연동 시험이 아니다.


## 홈 영상 관리·갱신

ADMIN 목록/미리보기/등록/수정/게시/전체순서/재검사7경로는 원문의 board revision 행 잠금, 등록100/게시3 제한, 중복ID와 전체순서 집합 검증을 유지한다. 기존 공개 HomeVideo11필드를 재사용하고 공개 GET은 변경하지 않는다. 외부 metadata 조회는 원문처럼 TX 밖이며 갱신은 만료 정리 커밋→최대50개 조회→각 board 잠금 반영 순서다.

`pawbridge.home-videos.management-enabled`는 기본 false다. 관리 true와 기존 canonical `pawbridge.jobs.refresh-enabled` 명시 true가 모두 있을 때만60초 뒤/1시간 주기 갱신을 조립한다. 기존 flag=false는 항상 거부하며 미지정도 후보에서는 off다. 원문 고정990은 canonical true/matchIfMissing=true였으므로 후보 기본 off는 알려진 활성화 차이다. 변경된 working 원문을 무조건 실행으로 본 초기 설명은 정정했고 원본 파일은 수정하지 않았다.

비작성 서브가16파일·30시험메서드와 원문 관리15본문/저장소11/YouTube client4본문·URL 변환을 대조했다. 고유69사례는 분할 성공 합집합이다. User/Report와 합친 새 조립1·466class 검사는4초에 통과했다. 실제 PG 잠금/rollback·YouTube·예약 true의 실행/활성 종료·JAR은 미실행이며 운영 스케줄러 소유권이 인계된 상태가 아니다. 고정 코호트의 공통 시험과 관리자 페이지 PG11을 이 통합 검증으로 확대하지 않는다.


## Community HTTP 편입 묶음

제보와 홈 영상 관리25경로를 이미 편입한 User HTTP 위에 묶는다. 독립 검수한22개 업무/시험 파일을 재사용하고 App·Security·조립시험3경로를 병합했다. 이후 무조건 제보 노출을 막기 위해 ReportConfig/HTTP와 공동Security/AppTest4경로의 기본 비활성을 보완했다. User의 Google/보호소/활동 조립·인가와 기존 공개 홈 조회를 보존한다. 기존 고정108에 Community25−공통3을 더한130 입력으로 공통 단위/구조·기존 페이지 PG11·소유 정리를 실행한다. 선택 PG11은 제보/YouTube/예약 또는 실제 writer 권한 검증을 대신하지 않는다. 새 운영 권한·설정·데이터·스케줄러는 변경하지 않는다.


### Google 실패·경합 보완과 남은 실제 연동 검증

자동 리뷰에서 원문에도 있던 같은 TX의23505 뒤 재시도와 최초 refresh 선조회/저장 경합을 찾았다. 회원 생성은 첫 TX의 rollback·cleanup이 끝난 뒤 새 TX에서 Google/LOCAL을 재조회하고 원래 한도인 추가1회만 시도한다. 동시 요청이 이미 만든 Google 계정은 재사용한다. refresh는 발급 순서를 보존한 뒤 같은 기존 writer TX에서 회원 PK를 FOR UPDATE로 잠그고 refresh를 재조회·저장한다. 사라진 회원·복수 refresh 행은 임의 보정하지 않고 실패한다. 이는 기존 실패/경합 동작의 의도적 보완이며 source byte 동일성으로 표현하지 않는다. 새 DDL/UNIQUE/데이터 보정은 없다.

Google 가입/저장 단위16건은 RED9 실패→GREEN16으로 rollback 종료/유한 시도/잠금 전 조회 금지와 실패 전파를 보호한다. 실제 PostgreSQL23505 복구·동시 row lock·선택 writer의 SELECT/행잠금/DML 권한은 아직 검증하지 않았다. 현재 OAuth 웹 호출에는 outer TX가 없으며, 다른 refresh writer까지 직렬화되거나 모든 isolation에서 동일하다고 주장하지 않는다. Google 활성화 전 실제 DB/격리수준·브라우저 흐름을 확인해야 한다.

오류 redirect에 기존 query가 있으면 두 번째 물음표가 붙는 결함도 URI builder로 보완했다. 새2 RED 실패→기존 포함7 GREEN, 기존 오류 문구·query 없는 응답·토큰 미기록을 유지했다. fragment 시험은 handler 경계이며 Boot의 fragment 거부 정책은 그대로다.


### 제보·홈 관리의 활성화 전 권한 인수

합성 CI 계정은 제보·홈 관리 전체를 지원하는 운영 계정이 아니다. Reader/Writer는 필수 외부 설정으로 선택되며, 기존 fixture 역할을 그대로 적용하면 새 조회·저장에 실패한다. 제보 기본 off와 홈 관리 기본 off를 유지하고 선택 계정의 실제 테이블/열·잠금·생성키 권한과 격리 요청·외부 연동을 확인하기 전 true/라우팅/예약 인계를 하지 않는다. 기존 strict 최소권한 계약을 느슨하게 만들거나 실제 GRANT를 실행하지 않았다.

제보 기존활성 HTTP16과 비활성5경로의403·호출0, 기본off/명시true조립2의 실제23사례는 RED6→GREEN23/3XML/각5초로 검증하고 비작성 검수했다. 변경4파일/11메서드 전수 대조, 새 프로덕션 타입·architecture 반복은 없다. 이 결과는 실제 PG 권한/SQL·R2·활성 서버 검증이 아니다.


### 본인 스토어 내정보 조회

`pawbridge.user-store-mypage.enabled=true`와 명시적인 `service.store.url`일 때만 `/api/v1/users/me/wishlists`, `/orders`, `/cart`의 GET을 조립합니다. 기본은 비활성이고 활성 요청은 기존 JWT에서 검증한 본인 ID만 사용합니다. Store 소유의 읽기 API를 호출하며 새 SQL·DDL·권한·장바구니 생성·이벤트 발행은 없습니다.

원문 `f06a72a06bd26662c647bde780dfc7a27bf7349c`의 회원 존재 확인 → 같은 읽기 TX 안 Store HTTP 순서를 유지하고, 기존 회원 프로필7열 조회와 기존 reader/TX를 재사용합니다. 원문의 exists 조회보다 넓은 기존 projection이며 응답으로 회원 정보를 내보내지 않습니다. 목록 Page/정렬/DTO·없는 장바구니204·없는 회원400·외부 오류500을 보존합니다. 예외 응답과 진단에는 외부 본문/인증값을 넣지 않습니다.

후보 HTTP는 연결10초·읽기60초·redirect 금지입니다. 기존 읽기 TX의3초는 JDBC 예산이며 HTTP3초 제한이 아닙니다. 실제 Store 응답/Feign 전체 변형·reader 권한·DB 연결 수명·부하/고장 복구는 별도 활성화 전 검증이 필요합니다. 합성 loopback/단위·JWT/MVC·조립/구조 검증을 실제 Store·PG 검증으로 표현하지 않습니다. 이 PR로 원래 User/Store 실행기나 Kafka/CDC를 종료하지 않습니다.

Store의 non-2xx 응답은 본문 변환 전에 세 조회에서 모두 거부합니다. 리다이렉트를 정상 Page나 없는 장바구니204로 받아들이지 않으며 Location도 따라가지 않습니다. 정상200/204와 기존4xx/5xx 실패는 유지합니다.


### Community 검색 재구축·이벤트 보존

`pawbridge.community-maintenance.enabled`는 기본 false입니다. 전체 기능과 `pawbridge.search.rebuild-enabled` 또는 `pawbridge.jobs.cleanup-enabled`를 각각 명시 true로 지정해야 해당 작업을 조립합니다. 두 하위 기능이 꺼져 있으면 스케줄러도 만들지 않습니다. 원문의 cleanup 기본 true와 다른 의도적인 활성화 경계입니다.

검색 재구축은 기존 writer의 Nori·해시·upsert를 재사용합니다. 같은 writer TX에서 최대100개의 누락·불일치 문서를 PK 순서로 잠그고 갱신하며 주기 기본값은5초입니다. 이벤트 보존은 별도 기본 TX에서 JVM 현지 시각의7일·30일 전보다 오래된 Outbox·Processed 행을 삭제하는 원문 정책을 유지합니다. 이 시간 기준이 CDC 전달 완료를 보증하지는 않습니다.

명시 활성화된 작업만 단일 소유 스케줄러를 사용합니다. 단위·조립 시험에서는 SQL 바인딩, 조건 분기와 실패 전파를 검증했고 실제 PG 잠금·rollback·삭제 권한·예약 실행·종료 대기는 검증하지 않았습니다. 기존 스케줄러 소유권, 대상 표·열과 행 잠금/UPSERT/DELETE 권한을 확인하기 전 활성화하거나 구 실행기를 종료하지 않습니다. 새 Kafka/ES 런타임·DDL·GRANT·운영 변경은 없습니다.

고정 입력 코호트는 기존140개에서147개로 확장했습니다. 새 파일6개와 기존 writer1개를 새로 고정하고 App·기본 조립 시험2개의 지문을 갱신했습니다. 기존138개 지문과 선택 검증/전체 회귀 구분, 기존 관리자 페이지 PG11 및 소유 정리를 유지합니다. 이 PG11은 이번 재구축·정리의 실제 DB 검증이 아닙니다.


### Animal 목록·상세·추천 조회 편입

Animal 업무 모듈은 목록/필터 검색·상세와 비슷한 동물 추천을 소유합니다. `pawbridge.animal-discovery.enabled`와 `pawbridge.animal-recommendations.enabled`는 각각 기본 false입니다. 명시 활성화된 기능의 정확한 GET 목록·숫자 ID 상세·숫자 ID 추천만 공개하고, 임의 nested·쓰기·기존 Gateway alias는 등록하지 않습니다. 실제 라우팅/프로젝터/worker 소유권 인계는 포함하지 않습니다.

기존 PG/Nori 검색의 필터·정렬·준비 상태 오류와 Page/DTO를 재사용합니다. 검색은 TX 진입 뒤 검증하고 count/content를 같은 읽기 REPEATABLE_READ/query5·TX5에서 처리합니다. 추천은 원본 조회가 끝난 뒤 Python을 TX 밖에서 호출하고, 다시 짧은 읽기 TX에서 후보를 검증합니다. 원격 순서/최대6 ID/자신·중복·없는 데이터·다른 종·상태 필터와 실패503을 유지합니다.

Boot는 기존 selected reader/TM과 단일 Nori 수명을 공유합니다. 추천 활성화 시 기존 `lost-search.python-url` → `python-ai-service.url` 우선순위를 따르고 둘 다 없으면 구성 실패입니다. 키는 런타임 인자로만 받습니다. URL이 있고 키가 없으면 ETC는 빈 결과, DOG/CAT는 요청503입니다. Feign core13.5의 연결3초/읽기45초·redirect 금지·애플리케이션 재시도 없음 계약을 재사용하며 새 Spring Cloud/legacy Animal 런타임을 넣지 않습니다.

source20·추천30·정책5·새 Boot11은 각 독립 합성 검증 결과이며 한 번의 전체 실행 합계로 표현하지 않습니다. TX 순서 지적의 신규 반례1은 RED→관련3 GREEN으로 닫았습니다. 새 조립은 실제 business525 검사와 일반 독립 검수를 통과했습니다. 실제 PG SQL/최소권한·락/격리·Python/TLS/AI·실행 JAR은 아직 미검증이며 기존 관리자 페이지 PG11을 Animal 검증으로 확대하지 않습니다.

CI는 검수한 Animal40경로와 변경 F9 guard를 포함한 고정184입력 및 알려진 workflow 지문을 확인합니다. JAR 허용 예외는 정확한 feign-core13.5 하나뿐입니다. 알 수 없는 workflow/입력은 선택 회귀를 거부하고 전체 회귀 경로를 유지합니다. 공통 selector를 바꾸지 않아 이번 workflow 변경의 기존 서비스3 검증은 그대로 수행합니다. 실제 이 단계의 CI 시간 절감을 보장하지 않습니다.


### Store 상품 탐색·상세 조회 편입

Store 업무 모듈은 상품 목록/검색/상세·SKU/옵션·카테고리 조회를 소유합니다. `pawbridge.store-catalog.enabled`는 기본 false이며, 명시 활성화된 정확5 GET만 고정 원문 Gateway처럼 공개합니다. 스토어 메뉴의 ADMIN 노출과 실제 조회 API의 공개 정책은 별개입니다. 새 ADMIN 제한을 도입하거나 기존 Gateway 별칭/쓰기 경로를 등록하지 않습니다. 실제 운영 라우팅/활성화는 포함하지 않습니다.

기존 PG 단일 CTE·Nori/준비 상태·정렬/Page·상태 필터와 금액/시간/NULL DTO를 재사용합니다. 상세는 원문처럼 status 필터나 view 증가 없이 조회하고, SKU/옵션·빈 그룹·카테고리 트리를 기존 값 구조로 반환합니다. JPA 조회는 기존 pawbridge_store 스키마의 명시 SELECT로 구현했습니다. Redis cache/lock을 재사용하지 않는 직접 조회이므로 성능·freshness·Redis 장애 경계의 동등성을 주장하지 않습니다.

동일 selected reader/TM을 사용하되 검색의 원문5초 query 예산을 위해 전용 JdbcTemplate을 조립합니다. readOnly/default isolation/default TX timeout을 사용하고, Nori 빈을 소유 종료합니다. 기존 공용3초 TX 예산을 강제하지 않습니다. 검색에도 읽기 TX를 추가한 알려진 후보 차이가 있습니다. 새 풀/계정/GRANT·DDL/JPA/Redis/Kafka는 추가하지 않았습니다.

원문 source8은44사례와 독립 검수를 통과했습니다. 후보 ADMIN 제한을 철회한 변경 HTTP36사례는4초에 성공했고 원본과 구39사례를 보존했습니다. 원문 유지의 최종 고유51은 분할 성공 합집합으로 단일 실행/75사례가 아닙니다. Animal과 공통8 조립은 새 TestKit3+기본 App1의4사례/12초 및 actual business542 검사에 성공했습니다. 기존44/36/11/전체 architecture는 반복하지 않았습니다.

Root는 공통 Gradle/Boot/정책/인가 합집합을 독립 대조했습니다. 고정 입력196개와 기존 known workflow/선택·전체 회귀·PG11·cleanup 계약을 유지합니다. 실제 PostgreSQL CTE/FTS·권한/view security·락/시간/타입/timeout·Redis/실행 JAR은 미검증이며, 기존 관리자 PG11은 Store 연동 시험이 아닙니다. 최소 reader 권한과 라우팅/단일 소유권을 확인하기 전 활성화하지 않습니다.


### User 내정보의 Store 모듈 호출

기존 `pawbridge.user-store-mypage.enabled`는 기본 false이며, 활성화 시 backend 기본값 http를 보존합니다. 명시 `pawbridge.user-store-mypage.backend=module`에서만 User의 회원 확인 트랜잭션을 끝낸 뒤 Store 공개 API로 본인의 위시리스트·주문·장바구니를 조회합니다. 이 모드에 HTTP origin/client는 필요 없고, 알 수 없는 backend는 조립을 거부합니다. 본인 JWT와 기존 3GET·cart 없음204 계약을 재사용합니다.

Store는 동일 reader/TM에 별도 readOnly REPEATABLE_READ·TX5/query5초를 조립합니다. 주문 헤더·상품 조회를 같은 스냅샷에 두기 위한 후보 선택이며 원문 기본 격리/timeout과 완전히 같다는 주장은 아닙니다. User→Store.api만 허용하고, Community→User→Store의 실제 resolved artifact는 허용하면서 Community의 Store 직접 의존/실제 API 참조/임의 파일 우회는 계속 거부합니다.

Store 공개 API/JDBC 최소30사례와 User 어댑터25사례, 새 Boot4/TestKit4의8사례·12초 및 실제 business556 클래스 검사가 통과했습니다. 성공 사례는 반복하지 않았고 실제 SQL/권한·동시성·NULL/collation/시간·GRANT/라우팅·운영 활성화는 미검증입니다. Wishlist의 페이지/옵션은 단일 CTE로 처리합니다. 임의 JPA 중첩 sort 전체 호환은 주장하지 않으며, 기존 User의 sort 쉼표 flatten 동작과 고정 Store Feign serializer의 배열 순서를 비교해 보존합니다.


### Animal 보호소·통계 조회 편입

`pawbridge.animal-shelters.enabled`와 `pawbridge.animal-statistics.enabled`는 각각 기본 false입니다. 보호소는 정확 일반4GET과, 명시 `pawbridge.animal-query.backend=postgresql`에서만 discovery/observations2GET을 등록합니다. 통계는 정확공개3GET·ADMIN2GET만 기존 JWT/role 보호로 연결합니다. Gateway legacy alias나 임의 인접경로·쓰기·배치는 등록하지 않습니다.

기존 보호소 조회·날짜/페이지·관측/미발견/오류 계약과 통계의 happen_date/APMS수정일/created_at+9h의 서로 다른 집계 날짜를 보존합니다. 통계 기간은 공개 default와 관리자 intake-trend366일 검증을 분리하고, 빈 날짜/상태/지역을 임의 보충하지 않습니다. 입양 통계는 실제 입양 이벤트 수가 아닌 기존 현재ADOPTED+오늘APMS수정 의미입니다.

동일 reader/TM을 사용하고 보호소 discovery만 기존 RO/RR·5초를, 일반보호소/통계는 원문 RO/default isolation/timeout을 유지합니다. 새 풀/TM/analyzer/외부요청/예약을 추가하지 않습니다. 보호소 source56·새조립12/8초, 통계 source36·새조립7/7초와business578 검사가 통과했습니다. 성공시험은 반복하지 않았으며 실제PG집계/SQL·최소GRANT·격리/콜레이션/시간·실행JAR/운영활성화는 미검증입니다.

User→Store 모듈 연결과 Animal 보호소/통계를 실제 함께 조립한 뒤, 변경된 조합의 기존 기본 App 시험 클래스8사례와 생산 컴파일·business592 검사를8초에 확인했습니다. 신규8계약으로 세지 않습니다. 원문 source/HTTP/전체 구조 시험은 반복하지 않았습니다. 고정 입력230 시험1은0.012초에 성공했습니다. 이는 실제 서비스·데이터베이스 실행 시험이 아닙니다.


### Store 위시리스트 명령 편입

`pawbridge.store-wishlist.enabled`는 기본 false입니다. 명시 활성화 시 정확 POST/DELETE `/api/v1/wishlists` 두 경로만 기존 JWT 인증에 연결하며 인증 principal의 회원 ID만 사용합니다. body/query/임의 X-User 헤더는 회원 ID로 신뢰하지 않습니다. 원래 소유권 검사가 없던 ID 삭제 HTTP는 등록하지 않습니다.

별도 명시 `STORE_WISHLIST_DB_WRITER_USER`/`STORE_WISHLIST_DB_WRITER_PASSWORD`만 쓰는 max1/minIdle0 writer를 같은 검증된 DB URL에 조립하고 reader 계정 재사용을 거부합니다. pool close 소유권·query5/TX10초·Clock.systemDefaultZone을 명시합니다. 실제 비밀값/계정/GRANT는 변경하지 않습니다. 회원/SKU 중복 선검사·현재 상품/옵션 응답·JVM시간 auditing·원래 오류 순서를 보존합니다. 유일제약 없는 동시 중복 추가는 기존 한계/활성화 gate이며 무조건 안전하다고 주장하지 않습니다.

source25사례·일반 독립검수와 새 합집합 Boot22사례·production compile/Business600 검사가8초에 성공했습니다. 원래 Boot22와 합집합22를44고유사례로 합산하지 않습니다. 위시리스트 source12 조립 단계에서 공통 App/보안/Storebuild 외 다른803부모파일과 source6을 보존했습니다. ID삭제 소유정책·실제PG/RETURNING/시간정밀도·writer 최소권한/identity sequence·commit/rollback·동시성·실제pool 종료는 미검증이며 활성화 전 확인해야 합니다. 운영·DB·결제/재고/Outbox/Redis·기존 데이터 변경은 없습니다.

별도의 새 PG17.11 ARM64 컨테이너1에서 실제 SELECT/CTE·INSERT RETURNING·owner DELETE의 합성 SQL12사례가 psql 단일 세션으로 성공했습니다. networknone/포트0/hostmount0/volume0·tmpfs·512MiB/CPU1 한도였고 해당 컨테이너만 정상 종료·삭제했습니다. 이는 JDBC/ResultSet 매핑·최소권한·인가·rollback/동시성의 증거가 아니며, source SQL의 schema/placeholder를 합성 값으로 바꾼 구문·결과 형태 확인입니다. 기존 서비스·DB·자격증명을 사용하지 않았습니다.


### 보호소 회원승인의 Animal 모듈 조회

기존 `pawbridge.shelter-membership.enabled`는 기본 false이고, `pawbridge.shelter-membership.animal-backend`의 기본값은 http입니다. 명시 module일 때만 승인 중 보호소 등록번호를 Animal 공개 API로 조회하며 외부 origin/client를 만들지 않습니다. User→Animal.api만 추가하고 Animal→User나 Community의 직접 Animal 의존/참조는 계속 차단합니다.

Animal API는 기존 전체 ShelterReads를 그대로 위임하고 같은 named reader/TM/ObjectMapper를 사용합니다. 기존 ShelterReads 빈이 없으면 동일 구현을 내부용으로 생성하며 공개 Shelter 경로를 켜지 않습니다. Reader RO REQUIRES_NEW와 기존 User writer는 분리합니다. 원문의 관리자/회원/잠금/상태검증·null/id/care 검증 및 원문 missing500→User503 분류를 보존합니다. 실제 Reader 권한·트랜잭션 suspend/resume·연결/잠금은 미검증입니다.

동결 Shelter source7사례, 같은구조 guard6 성공과 독립 검수를 보존했습니다. 활동 module을 제외한 새조합은2사례·production compile/Business605를6초에 확인했습니다. 기존 source/전체구조 성공시험은 반복하지 않았습니다. 활동조회의 특정 collection sort는 원문200/후보500 차이가 있어 해당 API/JDBC/어댑터/시험/선택 설정을 게시에서 전부 제외하고 별도 준비본에 보존했습니다. 기존 User 활동조회는 HTTP를 유지합니다. 활동 모듈·전체 HTTP 동등성·운영 활성화 완료를 주장하지 않습니다.


### User Outbox·Processed 보존 작업

`pawbridge.user-maintenance.enabled=true`와 canonical `pawbridge.jobs.cleanup-enabled=true`를 둘 다 명시할 때만 단일 소유 scheduler와 두 작업을 조립합니다. 기본 off는 원문 cleanup missing=true와 다른 안전 활성화 경계이며, 구 실행기·작업 소유권을 확인하기 전 켜지 않습니다.

원문처럼 Outbox created_at7일 전/Processed processed_at30일 전의 strict `<` 행을 상태·LIMIT 없이 삭제합니다. 각 작업의 TX 시작 뒤 JVM 현지 시각을 한 번 표본하고, UTC Calendar 바인딩과 별도 기본 timeout(-1)의 writer TX를 사용합니다. 기존 named writer JDBC/TM을 재사용하며 새 pool/DDL/GRANT는 없습니다. 예약은 JVM local03:00/03:30입니다. 삭제 예외는 TX 안에서 잡되 오류 타입만 로그로 남기고 begin/clock/commit/rollback 실패는 밖으로 전달합니다. 시간 기준 삭제가 CDC 전달 완료를 보증하지는 않습니다.

source 신규28사례는 User12와 Boot16의 분할 성공 근거이며 한 번의 전체 실행으로 표현하지 않습니다. source에서 발생한 Mockito 준비 오류·이전 XML/로그는 보존했습니다. 최종 결합은 생산 컴파일·실제 business package 검사와 고정252입력 시험1만 수행하며 기존28 성공시험은 반복하지 않습니다.

재사용 writeJdbc의 SQL5초 제한은 원문에 없던 상속 차이입니다. 실제 장시간 DELETE, JPA rollback-only와 PostgreSQL failed-TX/commit·rollback 차이, 최소 DELETE 권한/ACL, 시간 바인딩, 실제03:00/03:30 예약·종료와 단일 실행기 소유권은 미검증입니다. 활성화·구 실행기 종료 전 이를 확인해야 합니다. 실제 DB·데이터 삭제·예약·운영 변경은 하지 않았습니다.

### 본인 찜상품 항목 관리

`pawbridge.store-wishlist.enabled=true`에서만 JWT 회원의 `DELETE /api/v1/wishlists/{wishlistId}`를 허용합니다. body·query·헤더의 회원 ID 대신 검증된 principal을 사용하며, 항목 ID와 회원 ID를 같은 DELETE 조건에 묶습니다. 없음·다른 회원 소유·반복 삭제는 동일한 204이고 DB/TX 오류는 성공으로 숨기지 않습니다. 기존 POST와 SKU 기반 DELETE는 유지합니다.

회원 찜 조회 정렬의 `skuPrice`·`sku_price`는 기존 `sku.price`와 같은 컬럼을 사용합니다. Store의 전체 재귀·컬렉션 JPA 정렬 호환성은 아직 미완료입니다. 이 변경과 합성 시험·CI는 운영 배포 또는 전체 회원 조회 전환 완료를 뜻하지 않습니다.

## User 동물 조회와 로컬 찜 이벤트 연결

`pawbridge.user-animal-activities.animal-backend`는 기본 `http`이며, 명시 `module`일 때만 Animal의 순수 Java `AnimalMemberReads`로 본인 동물·찜 목록을 읽는다. 보호소 API와 같은 reader를 사용하지만 공개 Animal 경로를 추가로 활성화하지 않는다. reader는 별도 읽기 manager의 read-only REQUIRES_NEW이며 User writer와 분리된다. 원문 MANUAL 목록, 입력 ID 순서·중복/부재 처리, nullable 필드, JPA 속성 경로 정렬과 페이지 중복 제거 의미를 유지한다. 후보 SQL의 owned PostgreSQL 관측과 원문 JPA 실행 비교는 구분한다.

`pawbridge.favorite-delivery.enabled=false`가 기본이다. 활성화에는 User 활동 producer의 명시 `true`, `user-outbox-floor`와 `animal-outbox-floor`의 명시적인 0 이상 값이 필요하다. batch-size는 1..100(기본10), delay-ms는 100 이상(기본1000)이다. 자동으로 기존 최대 ID를 운영 시작점으로 정하지 않는다. old consumer/CDC 인계, 배타적 기동, 시작 floor 및 기존 보존 작업·최소 권한을 확정하기 전 운영에서 켜지 않는다. User의 무조건 보존기간 삭제가 함께 켜지면 시작을 거부한다. Animal의 기존 보존 작업도 인계 때 별도로 통제해야 한다.

User의 Favorite·Outbox 쓰기는 기존 트랜잭션을 유지한다. 전달기는 User pending 읽기 TX를 끝낸 다음 Animal 공개 API를 호출하고 별도 User TX로 자신의 Outbox UUID를 확인한다. Animal은 payload UUID 영수증과 찜 수·updated_at을 한 TX로 바꾸며, 감소는 기존 값이 양수일 때만 수행한다. API 호출 중 User 연결을 잡거나 같은 단일 writer pool에 REQUIRES_NEW를 중첩하지 않는다.

ADDED는 4회 시도 후 새 Animal TX에서 영수증을 재확인하고 필요한 보상 Outbox·해결 영수증을 함께 저장한다. REMOVED 실패는 pending으로 남긴다. 보상은 payload UUID로 User 영수증과 Favorite 삭제를 같은 TX로 처리하고 Animal은 별도 Outbox UUID로 ACK한다. UUID 종류와 originalEventId를 혼동하지 않는다. commit 응답 유실을 rollback으로 단정하지 않고 영수증으로 재관측한다. 원문의 늦은 ADDED 보상이 나중에 재추가한 Favorite를 제거할 수 있는 세대 구분 한계는 유지되며 새로 해결했다고 표현하지 않는다.

선택은 고정 floor에서 캡처한 유한 upper까지 aggregate별 첫 미완료 행을 순회한다. cursor는 성공한 읽기의 raw 마지막 ID로만 이동하며 완료 표시는 아니다. 실패한 aggregate를 보존하면서 다른 aggregate를 선택하고, 순회 끝에는 floor로 돌아가 늦게 commit된 낮은 ID도 다시 확인한다. 같은 aggregate의 미완료 선행 행은 후속을 계속 막는다. 잘못된 보상 행은 pending으로 남기고 같은 batch의 정상 행만 반환한다. DB/read commit 실패에는 cursor를 옮기지 않는다. 아직 보이지 않는 낮은 ID까지 포함한 완전 FIFO를 보장하지 않는다.

단일 전용 scheduler는 지연 후 실행하고 context 종료 시 먼저 cooperative stop을 요청한다. 대기 15초는 정상 종료 완료를 입증하는 근거가 아니다. 로그는 고정 문구·실패 개수/예외 타입만 남기며 payload·계정·인증값은 기록하지 않는다. 신규 테이블/스키마 변경, Kafka/CDC 종료, 운영 활성화는 이번 코드 편입에 포함하지 않는다.

합성 PG 시험은 `owned-postgresql` 태그로 일반 `test`/CI에서 제외한다. 전용 `:boot:ownedPostgresqlTest --tests ...`는 새 전용 환경만 사용하며 0건 또는 skip이 있으면 실패한다. 동물 조회는 `PAWBRIDGE_ANIMAL_MEMBER_PG_ENABLED=owned-fixture-only`와 시험이 검증하는 전용 환경값, 이벤트는 엄격한 권한의 `P6_PG_SETTINGS` 파일이 필요하다. 두 클래스는 각각 별도로 선택해야 하며 실제 계정·접속값은 예시나 Git에 넣지 않는다. SQL resource는 새 시험 DB 준비용으로만 보관한다. 시험 자체는 DDL/권한 변경을 하지 않고 생성한 행만 정리한다.

## Store 회원 목록의 원문 JPA 재사용

명시 `pawbridge.user-store-mypage.enabled=true`와 `backend=module`일 때 주문·찜 목록은 원래 Store의 model/enum/base13개와 DTO2개를 패키지·import만 바꿔 재사용한다. Repository2개는 ID 페이지 조회와 선택한 부모의 상세 graph 조회로 확장한다. 정렬은 이전 모듈 JDBC가 지원한 찜25·주문12 scalar 속성을 모두 보존하고, 찜에는 한 단계 부모 카테고리 id/name만 추가해 정확27/12개를 허용한다. underscore/camel alias는 기존 Spring Data 속성 해석을 사용한다. 재귀 경로와 컬렉션 정렬, 정렬5개 이상·size2000 초과는 Store 읽기 트랜잭션과 SQL 전에 고정 오류로 거부한다. 이번 PR 초기에 추가했던 컬렉션 정렬은 부모 페이지를 증폭하므로 의도적으로 제외한다. 기존 User Runtime 오류 응답·기본 HTTP backend·순수 Store API를 유지하고 Cart는 기존 JDBC 읽기를 사용한다.

Store EMF는 entity9와 mapped superclass1만 명시하고 기존 reader pool을 공유한다. 별도 명명 JpaTM의 RO/RR/5초 안에서 entity→DTO 변환까지 마친다. 전역 JPA/entity/repository 자동스캔, OSIV, schema 생성·변경·validate는 켜지 않는다. schema는 pawbridge_store, 물리 naming과 UTC를 고정하며 bootstrap JDBC metadata 조회도 끈다. 기존 reader JdbcTM과 writer pool은 다른 업무에서 그대로 유지한다.

부모 ID의 closed projection만 먼저 SQL LIMIT/OFFSET으로 조회하고, 같은 RO/RR 트랜잭션에서 memberId와 선택 ID에 해당하는 graph만 가져온다. ID 페이지가 비면 graph를 조회하지 않으며, 부모 ID 페이지의 순서·total·Sort로 DTO를 조립한다. 선택 ID가 상세 결과에서 빠지면 부분 성공으로 반환하지 않는다. 새 임의 ID tie-break나 자식 MIN/MAX 정렬은 추가하지 않는다. 선택한 부모의 자식 전체는 여전히 로드하므로 고정 메모리 사용을 보장한다고 표현하지 않는다. 읽기5초 제한은 기존 모듈 경계이며 원문 무제한 실행과 구분한다.

합성 PostgreSQL의 9개 원문 테이블 모양과 고정 데이터에서 별도 원문/후보 EMF를 비교한다. 안전한 정렬·부모 페이지·count·Sort metadata·nullable·구매 snapshot, SQL 부모 페이지/선택 ID graph·SELECT-only 권한·읽기 불변·연결 반환을 보호한다. 최초 전체 graph 방식의 27사례와 이후 보안 회귀 근거는 별도 이력으로 보존하며, 새 2단계 방식의 성공은 해당 수정본의 실제 실행 결과로 판정한다. 기존 Store 전체 migration·결제·실제 운영 검증은 아니다. 원문17 Java는 compileTestJava의 고정 시험 입력이며 main/실행 JAR에 포함하지 않는다. 새 extractor/sourceSet/JAR HTTP 도구는 없다.

`P7_PG_SETTINGS`의 제한된 파일로 전용 `:boot:ownedPostgresqlTest --tests com.pawbridge.backend.boot.configuration.OwnedPostgresqlStoreMemberJpaTest`만 선택한다. SQL resource는 새로 만든 marker 검증 DB에 한 번 적용하는 준비 자료이며 Java 시험은 SELECT만 수행한다. 0사례/skip은 실패이고 일반 CI는 이 owned 태그를 제외한다. 키·접속값·시험 산출물은 Git에 넣지 않는다.


## Animal JSON 명령과 현재 회원 권한

`pawbridge.animal-commands.enabled`는 기본 false이며 대소문자를 구분하지 않는 명시 `true`만 허용한다. 이 값이 없거나 `on`인 경우 명령과 보안 경로는 비활성이다. 기존 동물 조회·추천은 각자의 flag를 유지한다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| POST | `/api/v1/animals` | 원문 동물 생성, 201 |
| PATCH | `/api/v1/animals/{id}/status` | 원문 상태 변경, 200 |
| PATCH | `/api/v1/animals/{id}/description` | 원문 설명 변경, 200 |
| DELETE | `/api/v1/animals/{id}` | 원문 삭제, 204 |

서명 검증한 JWT의 회원 ID만 사용한다. User 모듈은 같은 writer 트랜잭션에서 현재 회원 행을 잠그고 삭제 대기를 조회한다. Animal은 현재 DB의 `ROLE_ADMIN` 또는 보호소 등록번호가 일치하는 `ROLE_SHELTER`만 허용한다. JWT의 오래된 역할이나 `X-User-*` 헤더로 이 검사를 대체하지 않는다. 기존 조회·ADMIN 경로의 인가는 바꾸지 않는다.

선택한 원문 JPA 모델·저장소·mapper·업무 로직을 infrastructure 안에서 재사용한다. 명시 writer EMF는 Animal/Shelter/OutboxEvent/BaseTimeEntity만 관리하고 전역 스캔·DDL·새 pool을 만들지 않는다. 원문 감사 listener와 서비스의 REQUIRED 경계를 유지하고 writer 격리는 READ_COMMITTED로 명시한다. JpaTM의 기본 제한은 15초다. 조회용 reader와 구분하고, User 조회와 검색 SQL도 같은 writer DataSource에 참여해야 한다. Nori 분석기는 조회·추천·명령 사이에서 하나를 공유하고 context와 함께 닫는다.

생성과 설명 변경은 원문 동물·검색 투영·Outbox를 함께 커밋한다. 비MANUAL 출처의 공고번호가 null/빈 값이면 쓰기 전에 고정 400으로 거부하며, MANUAL의 기본값·번호 생성과 정상 외부 번호는 유지한다. 상태 변경은 Outbox 저장 후 flush한 감사 시각으로 응답을 만든다. 상태 변경의 원문 이벤트와 삭제의 FK 동작도 유지하며 삭제 이벤트를 새로 만들지 않는다. 원문 payload eventId와 Outbox event_id는 별도 UUID다. 이 Outbox의 전체 전달기·CDC 교체, APMS/Tour/사진 보관 작업, R2·AI 실행과 운영 활성화는 이번 명령 편입에 포함하지 않는다.

초기 보완에서는 소유 합성 PostgreSQL 17.11의 실제 보안→MVC→JPA 16계약과 당시 코어 단위 48사례를 확인했다. 이후 동시성 보완의 현재 검증은 아래에서 구분한다. PG는 최초 실행의 15성공과 UTC Calendar로 비교를 교정한 상태 응답 1사례의 수정 전 RED·수정 후 GREEN으로 검증했으며, 비교 방식 교정 뒤 성공한 나머지를 반복하지 않았다. 서버의 시간대·값·허용 오차는 변경하지 않았고 응답/DB 감사 시각 차이는 1µs 미만이어야 한다. 현재 DB 역할·보호소 소유권·삭제 대기, 네 명령·감사 시각·검색 revision, V7 charset 거부와 FK/Outbox 실패의 rollback·정상 재시도, READ COMMITTED·쓰기 트랜잭션·단일 pool/현재 회원 행 잠금, 응답 검증 실패 후 정확한 요청 표식으로 정리하는 경로를 포함한다. User 표는 actor용 열만 갖는 축소 fixture이며 전체 User/Animal migration이나 운영 권한의 검증을 뜻하지 않는다. 기본 off와 비소유 flag alias의 HTTP8은 별도 통과했다.

전용 시험은 `P8_PG_SETTINGS`의 제한된 소유 파일과 `:boot:ownedPostgresqlTest --tests com.pawbridge.backend.boot.configuration.OwnedPostgresqlAnimalCommandTest`로만 실행한다. SQL resource는 새 DB/marker 확인 후 소유 관리자가 한 번 준비하는 자료다. 실제 계정·키·접속값은 Git에 넣지 않는다. 일반 CI는 owned 태그를 제외하며 이 PG 시험을 실행했다고 표시하지 않는다. 기존 운영의 DML·행 잠금·감사 및 단일 실행기 소유권을 인계하기 전 flag와 공개 라우팅을 활성화하지 않는다.


### 동시 Animal 명령과 자동 공고번호

현재 회원 행을 먼저 잠근 다음 같은 writer TX에서 Animal을 PESSIMISTIC_WRITE로 조회해 상태·설명 수정과 삭제를 직렬화한다. 두 사용자의 동시 수정이 서로의 필드를 덮어쓰지 않으며 기존 소유권 검사·Outbox·검색 투영의 원자성은 유지한다.

자동 MAN 번호는 실제 쓰기 TX와 명시 READ_COMMITTED 메타데이터가 있을 때만 생성한다. 날짜를 한 번 캡처하고 날짜별 PostgreSQL transaction advisory lock SQL을 실행한 다음 별도 statement로 정확한 6자리 suffix MAX를 읽는다. 중간 행 삭제 후 현존 최대값을 재사용하지 않고 999999를 초과하는 번호는 거부한다. 잠금과 MAX를 한 SQL/CTE로 합치지 않는다. 명시 제공 공고번호는 원문상 고유 식별자가 아니므로 기존 허용을 유지한다. 모든 번호의 전역 UNIQUE, 비협력 writer·임의 제공번호와의 경합, 삭제된 최고번호의 영구 재사용 금지를 보장하는 계약은 아니다.

이번 수정의 단일 오프라인 호출 10초에서 코어 단위 59사례와 신규 동시성 PG3·영향 기존 PG2가 모두 통과했다. 서로 다른 ADMIN/SHELTER와 실제 두 PG PID의 잠금 대기를 관찰해 동시 상태·설명 보존과 동시 자동번호의 분리를 검증했고 중간 번호 삭제도 재현 후 회복했다. 3개 신규 계약은 기존 코드에서 먼저 RED였다. 다른 성공 PG/JAR와 전체19사례를 반복 실행한 결과가 아니다. 시험은 pool 한도를 임시2로 늘린 뒤 worker 종료가 확인됐을 때만1로 복귀하고 정확한 소유 행을 정리한다. worker 종료가 불확실하면 후속 시험·행 삭제·pool close를 거부해 근거를 보존하며 실패 경로의 전체 자원 정상 종료까지 주장하지 않는다. 이 시험과 mock TX metadata 단위 검증은 운영 또는 실제 AI 검증이 아니다.

READ_COMMITTED 명시 변경 뒤 기존 Boot 구성 검증의 기본 격리 수준 기대값 한 곳을 같은 RC 계약으로 맞췄다. REQUIRED·기본 timeout·writer 연결·readOnly 단언은 유지했다. 해당 구성 클래스12사례를 별도7초 호출로 통과했고 PG·업무 단위·JAR를 반복하지 않았다. 고정 CI 입력은 이 구성 시험까지 실제 source hash로 결속한다.


## 바로구매와 본인 주문 상세 편입

`pawbridge.store-direct-orders.enabled=true`에서만 `POST /api/v1/orders/direct`를 조립한다. `GET /api/v1/orders/{orderId}`는 Direct 또는 Cart flag가 명시적으로 true일 때 공유 컨트롤러 한 개로 조립한다. 기본값과 `on` 별칭은 비활성이다. 검증된 JWT 회원 ID를 사용하고 현재 회원·삭제 대기를 확인한다. 주문·상품/SKU 잠금·전체 SKU Outbox·PostgreSQL 검색 반영은 같은 writer JPA 트랜잭션에 참여한다. 주문 상세는 같은 한정 persistence unit의 별도 읽기 전용 트랜잭션에서 조회한다. 기존 MyPage reader는 그대로 유지한다.

기존 주문 응답과 구매 당시 상품명·가격·수량 스냅샷을 보존한다. 수량/금액 overflow는400, 기존 재고/상품 상태 오류는500이며 rollback charset CHECK에 한정한 원문400/C007도 유지한다. overflow·검색·Outbox·주문 저장 실패 시 재고까지 함께 롤백한다. 재요청은 새 주문이며 멱등키나 PENDING 자동 만료를 추가하지 않았다. 장바구니·Payment 및 외부 결제는 이 편입에 포함하지 않는다.

로컬 합성 PG 시험은 소유 marker와 제한 writer를 검증한 전용 `P9_PG_SETTINGS` 파일로 `:boot:ownedPostgresqlTest --tests com.pawbridge.backend.boot.configuration.OwnedPostgresqlDirectOrderTest`만 선택한다. 실제 비밀설정·생성 데이터·시험 로그·JAR는 Git에 넣지 않는다. ordinary test에서 owned PG 태그는 제외된다. 동일 상품 동시 구매·실제 행 잠금·전체 롤백·감사 시각/스냅샷·정확한 cleanup은 합성 환경의 근거이며 운영 검증이 아니다.

운영 활성화에는 새 실행기의 DB 권한·라우팅·이벤트 소비자 및 기존 Store 실행기와의 소유권 인계가 별도로 필요하다. 현재 운영·Kafka/CDC·장바구니·결제·복구 자료는 이 변경으로 제어하지 않는다.


## 새 사진 기반 실종동물 후보 검색 편입

`pawbridge.animal-lost-search.enabled=true`에서만 공개 multipart `POST /api/v1/animals/lost-candidates`를 조립한다. 기본값과 `on` 별칭은 비활성이다. `lost-search.python-url`이 `python-ai-service.url`보다 우선하며 서버 내부 키는 `python-ai-service.internal-api-key`에서 런타임에 주입한다. 사용자 헤더를 내부 키로 사용하거나 응답·키·원격 오류 본문을 로그에 남기지 않는다.

Python 응답을 기다리는 동안 DB 트랜잭션과 연결을 열지 않는다. 최대20개 후보를 검증한 뒤, SELECT 전용 reader의 짧은 읽기 전용 JPA 트랜잭션에서 현재 동물의 종·상태와 보호소 정보를 다시 조회한다. 원격 순서·중복 첫 evidence·기존 공개 DTO를 보존하며 내부 유사도 점수는 공개하지 않는다. 사진5MiB/추론 동시1 제한과 원문 오류·Retry-After·permit 복귀를 유지한다.

공개 활성화 전에는 기존 Gateway admission 계약(실제 요청 전체6MiB, 동시2, 인스턴스당 분당30회, 전체50초, 외부 인증·내부 키 헤더 제거)을 별도로 인계해야 한다. 서비스의5MiB 검사와 permit1은 multipart 파싱 전 메모리·전송 제한의 대체물이 아니다. 이 구성의 로컬 합성 성공만으로 공개 운영 준비 완료라고 판단하지 않는다.

`P10_PG_SETTINGS`의 소유 marker·SELECT 전용 계정을 검증한 경우에만 `:boot:ownedPostgresqlTest --tests com.pawbridge.backend.boot.configuration.OwnedPostgresqlAnimalLostSearchTest`를 선택한다. 이 시험은 새 합성 동물과 임시127.0.0.1 Python 응답 대역을 사용하고 Animal/Shelter 전체 행 불변을 확인한다. 실제 AI 품질·R2·운영 검증은 포함하지 않는다. 비밀설정·데이터·로그·실행 JAR는 Git에 넣지 않는다.


## 장바구니 관리와 주문 생성 편입

`pawbridge.store-carts.enabled=true`에서만 다음 흐름을 조립한다. 기본 off와 `on` 별칭은 닫혀 있고, Direct flag는 Cart API를 열지 않으며 Cart flag도 바로구매 POST를 열지 않는다.

| 메서드 | 경로 | 계약 |
|---|---|---|
| GET | `/api/v1/carts` | 기존 배열·품목 ID null 응답 |
| POST | `/api/v1/carts/items` | 기존 SKU 수량 누적, 200 빈 본문 |
| PATCH | `/api/v1/carts/items/{skuId}?quantity=...` | 수량 설정, 비양수는 제거 |
| DELETE | `/api/v1/carts/items/{skuId}` | 품목 제거, 200 빈 본문 |
| DELETE | `/api/v1/carts` | 전체 품목 제거, 부모 Cart 보존 |
| POST | `/api/v1/orders` | 저장된 Cart로 PENDING 주문 생성, 배송 4필드·기존 201 응답 |
| GET | `/api/v1/orders/{orderId}` | 현재 회원의 본인 주문·구매 당시 스냅샷 조회 |

신뢰할 수 있는 JWT principal과 현재 회원·삭제 대기를 확인하고, 같은 writer 트랜잭션의 회원 행 잠금으로 해당 회원의 Cart 변경과 주문 소비를 직렬화한다. Cart 두 표는 기존 migration 모델을 사용하며 Redis dirty 동기화 대신 DB에 즉시 반영한다. 기존 MyPage 조회의 Cart 없음과 저장된 빈 Cart 구분을 유지한다. 새 pool·DDL·전역 JPA scan은 만들지 않는다.

주문은 SKU ID만 먼저 추린 뒤 기존 상품/SKU 잠금·재고 차감·전체 SKU Outbox·검색 반영을 재사용한다. 잠금 후의 같은 현재 가격으로 합계와 OrderItem 스냅샷을 만든다. 이는 원문의 잠금 전 합계와 잠금 후 스냅샷 가격이 달라질 수 있던 경계를 강화한 변경이다. 주문 저장과 Cart 비우기까지 하나의 writer 커밋이며, Cart 삭제 후 커밋 실패도 재고·주문·이벤트·검색과 함께 롤백한다. 원문 배송 4필드에 새 필수·길이 규칙을 넣지 않는다.

누락 SKU만 남은 Cart는 400과 함께 보존한다. 유효·누락 SKU가 섞이면 기존처럼 유효 품목을 주문하고 전체 Cart를 비운다. 중복 SKU 저장 상태는 임의 보정 없이 실패하며, 금액·수량 overflow는 400이다. 새 멱등키나 PENDING 만료, 결제·환불 정책을 만들지 않는다. 동시에 같은 Cart를 주문하면 한 번 소비하지만, 두 주문 사이에 새 추가가 있으면 별도 주문이 생길 수 있다.

소유 PG 검증은 `P12_PG_SETTINGS`와 `:boot:ownedPostgresqlTest --tests com.pawbridge.backend.boot.configuration.OwnedPostgresqlStoreCartOrderTest`를 사용한다. 별도 새 DB·제한 writer·표식을 확인한 fixture에만 적용한다. 기존 `P11_PG_SETTINGS` Cart 전용 최소권한은 유지한다. 실제 비밀설정·생성 데이터·시험 로그·JAR는 Git에 넣지 않는다. ordinary CI는 owned 태그를 실행한 것으로 표시하지 않는다.

공개 활성화 전에는 기존 Redis 미동기화 Cart 인계, 기존 Cart API·동기화 실행기와 새 실행기의 배타적 소유권, DB 권한과 라우팅을 별도로 확인해야 한다. 이 소스 편입은 기존 운영·Kafka/CDC·외부 결제나 데이터·복구 자료를 제어하지 않는다.


## 관리자 주문 조회·배송 관리

`pawbridge.store-order-administration.enabled=true`일 때 관리자 주문 목록·필터/상세 조회와 배송 상태 변경의 세 API만 등록합니다. 기본값은 비활성이며 `on` 등 별칭은 허용하지 않습니다. 기존 Store writer EMF·DataSource·트랜잭션과 auditing을 공유합니다. 관리자 구성만으로 개인 주문 조회, 장바구니 또는 결제 API를 활성화하지 않습니다.

서명 검증된 관리자 JWT와 현재 DB의 정확한 `ROLE_ADMIN`을 모두 요구하며 삭제 대기·누락 계정은 거부합니다. 조회는 짧은 읽기 전용 트랜잭션, 변경은 같은 writer의 User 행 잠금 뒤 Order 행 잠금으로 실행합니다. 목록 크기는 1~100, 정렬은 명시된 Order의 scalar 필드로 제한합니다. 배송 변경은 주문의 결제 상태·금액·품목·재고·Outbox를 바꾸지 않습니다. 원문 계약인 단건 없음 404, 배송 변경 대상 없음 400, null 배송 상태의 500과 rollback을 유지합니다. null은 같은 쓰기 트랜잭션에서 현재 관리자와 주문을 확인한 뒤 DB 변경 전에 고정 오류로 거부해, DB 제약 오류에 수취 정보가 기록되는 경로를 막습니다.

새 합성 DB 검증은 `P13_PG_SETTINGS`의 비공개 설정을 사용하는 `OwnedPostgresqlStoreOrderAdministrationTest`로 분리합니다. 이 소스 편입은 운영 활성화나 Payment·취소·환불·재입고 계약 완료가 아닙니다.

### 관리자 주문 목록의 품목 일괄 조회

주문 페이지를 먼저 조회하고 해당 페이지의 ID에만 품목을 한 번에 연결한다. 컬렉션 fetch에 페이지 제한을 적용하지 않으며, 응답 순서와 전체 건수는 원래 Page를 유지한다. 비어 있는 페이지는 품목 조회를 생략한다. User 권한 조회는 별도 JDBC이며, 주문 조회는 페이지 SQL·필요한 count SQL·품목 SQL로 제한한다.

소유 P13의 새 회귀 1건은 수정 전 쿼리 상한 실패와 수정 후 성공으로 확인했다. 두 주문을 size100으로 조회할 때 Hibernate SQL2, 빈 첫 페이지 SQL1을 관측하고 품목·역순·total·15표 불변을 확인했다. 이 데이터의 count는 추론되므로 count SQL을 실제 실행했다는 근거로 확대하지 않는다. 기존 null 배송의 안전한500과 로그 차단은 유지한다.


## 공개 여행 장소 조회

`pawbridge.animal-travel.enabled=true`와 `pawbridge.animal-query.backend=postgresql`을 함께 지정할 때 `/api/v1/places/regions`, `/api/v1/places`, `/api/v1/places/{contentId}`의 세 GET만 활성화합니다. 기본 비활성이고 기존 reader JDBC·트랜잭션 관리자를 공유합니다. 지역·상세는 읽기 전용, 목록의 count와 page는 원문과 같이 같은 읽기 전용 REPEATABLE_READ 트랜잭션으로 조회합니다. 별도 pool·EMF·DDL은 추가하지 않습니다.

원문 PostgreSQL의 여행 카탈로그 7개 테이블과 응답·가용 상태·이미지 이용 조건을 사용하며, 응답 중 TourAPI·R2·AI 호출을 하지 않습니다. 수집·스케줄러·갱신 API는 이 구성에 포함하지 않습니다. 새 합성 DB 검증은 SELECT 전용 reader와 `P14_PG_SETTINGS`를 사용하는 `OwnedPostgresqlAnimalTravelTest`로 분리합니다. 기존 수집기의 실행 소유권 인계와 실제 운영 활성화는 별도 미완료 항목입니다.
