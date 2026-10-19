# PawBridge 업무 통합 실행기

Java 21·Gradle 8.14.3·Spring Boot 3.4.11의 독립 멀티프로젝트다. F2의 게시글 단건 GET·작성자 닉네임 조회, F3의 인증된 일반 게시글 작성, F4의 본인 게시글 삭제, F5의 본인 게시글 수정, F6의 공개 게시글 목록, F7의 공개 게시글 검색, F8의 공개 댓글 목록, F9의 인증된 댓글 작성, F10의 본인 댓글 삭제, F11의 본인 댓글 수정, F12의 인증된 본인 정보 조회, F13의 본인 닉네임 수정, F14의 본인 찜 여부 조회, F15의 공개 홈 영상 조회, F16의 공개 오늘 방문자 합계 조회, F17의 인증된 쪽지 수신자 확인 조회, F18의 표시 작성자 중 본인 차단 여부 조회를 편입하는 후보이며 운영 Gateway를 이 실행기로 연결하거나 기존 서비스를 종료하지 않는다.

## 업무 경계와 호출

| 모듈 | 책임 | 직접 프로젝트 의존성 |
|---|---|---|
| boot | MVC·보안·DB·업무 구현 조립과 실행 JAR | user, community |
| user | 공개 닉네임/연락 회원 조회 계약·내부 본인 정보 조회·본인 닉네임 수정·찜 여부 조회와 JDBC·HTTP 구현 | 없음 |
| community | 게시글 단건·목록·검색·작성·수정·삭제·댓글 목록·작성·수정·삭제·미디어·검색 projection·공개 홈 영상·오늘 방문자 합계·쪽지 수신자·표시 작성자 차단 상태 정책·REST 응답 | user |

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

F18의 새 RO TX에서 연결 생성·시스템 완료·deadline 실패는 구체적인 CannotCreateTransactionException/TransactionSystemException/TransactionTimedOutException만 저장소503으로 분류한다. User typed 실패는 회원503, transaction usage와 그 외 Runtime은 일반 쪽지503을 유지한다. 원본 autocommit의 DAO 연결 실패가 후보의 TX 예외로 감싸지는 기술적 차이에 대한 명시적 적응이다.

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

정확한 `GET /api/v1/visits/today`는 기존 `pawbridge.visits.enabled` 설정과 `DAILY_VISITORS_ENABLED` 환경 변수의 기본 false를 유지한다. true일 때만 조회 실행자·reader·controller·advice를 조립하며 서명키·VisitCookie·원본 lifecycle·시작 INSERT·집계 POST·정리/예약 작업은 등록하지 않는다. 공개 GET 보안 matcher는 두 상태에서 같고 false/누락은404다. 이404 본문을 원본 global envelope와 동일하다고 주장하지 않으며 미편입 경로를 열지 않는다.

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
20. always cleanup은 현재 run owner label의 합성 컨테이너만 정지/제거한다. 일반 prune·다른 컨테이너/볼륨 조작은 하지 않는다.

`scripts/f2_ci.py`·`f3_ci.py`·`f4_ci.py`·`f5_ci.py`·`f6_ci.py`·`f7_ci.py`·`f8_ci.py`·`f9_ci.py`·`f10_ci.py`·`f11_ci.py`·`f12_ci.py`·`f13_ci.py`·`f14_ci.py`·`f15_ci.py`·`f16_ci.py`·`f17_ci.py`·`f18_ci.py`·`f3_s3_stub.py`의 실행 가드는 이 저장소 GitHub-hosted Linux와 고유 run ID를 요구한다. DB mutation 전에는 loopback/정확한 시험 DB/marker/admin/reader를 대조한다. 미설정 테스트를 skip 성공으로 처리하지 않는다. `scripts/f2_fixture.sql`·`f3_fixture.sql`·`f4_fixture.sql`·`f5_fixture.sql`·`f7_fixture.sql`·`f8_fixture.sql`·`f9_fixture.sql`·`f10_fixture.sql`·`f11_fixture.sql`·`f12_fixture.sql`·`f13_fixture.sql`·`f14_fixture.sql`·`f15_fixture.sql`·`f16_fixture.sql`·`f17_fixture.sql`은 CI 전용이며 앱 resource/JAR에 포함되지 않는다.

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

TestKit은 Gradle logging 충돌을 막기 위해 architectureTest 전용 classpath를 사용한다. 실제 업무 build와 guard를 fixture에 복사하고, 본 빌드가 해결한 BOM/의존 cache를 offline으로 사용한다. 허용/금지 fixture의 이유를 검사하며 일반 컴파일·다운로드 오류를 구조 차단 성공으로 취급하지 않는다.

Wrapper checksum 고정과 기존 최소 메모리·worker 1 설정을 유지한다. CI는 테스트 XML·source/runtime·oracle hash·packaged JAR hash/기동/종료를 7일 보존한다. Mac ARM64 운영 기동·전체 서비스 통합·RAM 절감의 증거와 구분한다.

전체 결정·계획·문제 해결 기록은 Obsidian `Projects/pawbridge`의 F2·F3·F4·F5·F6·F7·F8·F9·F10·F11·F12·F13·F14·F15·F16·F17·F18 계획과 모듈형 모놀리스 전환 노트를 따른다.
