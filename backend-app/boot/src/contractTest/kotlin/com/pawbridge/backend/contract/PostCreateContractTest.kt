package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Creating and reading a post through the whole application (replaces the packaged F3 smoke).
 *
 * The real SDK client uploads to [ContractFakeS3]; the database is the Testcontainers PostgreSQL and the
 * application role holds only the column privileges of `schema/app-grants.sql`.
 */
class PostCreateContractTest : ApiContractTestBase() {
    @BeforeEach
    fun seedAuthor() {
        seedUser(AUTHOR, NICKNAME)
    }

    @Test
    fun `거부되는 토큰일 때__글을 작성하면__401 봉투를 주고 아무것도 바뀌지 않는다`() {
        // given
        val files = listOf(Upload("a.jpg", "image/jpeg", "synthetic".toByteArray()))

        // when
        // then
        assertNoSideEffects {
            for ((name, token) in rejectedTokens(AUTHOR)) {
                val reply = create(token, files = files, headers = FORGED_HEADERS)
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.code).`as`(name).isEqualTo(401)
                assertThat(reply.data.isNull).`as`(name).isTrue()
                assertThat(reply.message).`as`(name).isEqualTo(rejectionMessage(name))
                assertThat(reply.headers.firstValue("Set-Cookie")).`as`(name).isEmpty
            }
            val missing = create(null, files = files, headers = FORGED_HEADERS)
            assertThat(missing.status).isEqualTo(401)
            assertThat(missing.message).isEqualTo("인증 토큰이 필요합니다.")
        }
    }

    @Test
    fun `실종 또는 제보 게시판일 때__글을 작성하면__전용 화면 안내로 400이고 아무것도 바뀌지 않는다`() {
        // given
        val files = listOf(Upload("a.jpg", "image/jpeg", "synthetic".toByteArray()))

        // when
        // then
        assertNoSideEffects {
            for (board in listOf("MISSING", "REPORT")) {
                val reply = create(accessToken(AUTHOR), board = board, files = files)
                assertThat(reply.status).`as`(board).isEqualTo(400)
                assertThat(reply.message).`as`(board).isEqualTo("실종·목격 제보는 전용 등록 화면에서 작성해 주세요.")
            }
        }
    }

    @Test
    fun `잘못된 입력일 때__글을 작성하면__거부되고 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        assertNoSideEffects {
            val body = Multipart.build(mapOf("content" to "필수파라미터", "boardType" to "COMMUNICATION"), emptyList())
            val missingTitle = call("POST", "/api/v1/posts", token, body = body.bytes, contentType = body.contentType)
            assertThat(missingTitle.status).isEqualTo(400)
            assertThat(missingTitle.message).isEqualTo("필수 요청 파라미터 'title'(이)가 누락되었습니다.")

            assertThat(create(token, board = "NOT_A_BOARD").status).isEqualTo(500)
            val badFormat = create(token, files = listOf(Upload("bad.txt", "text/plain", "forbidden format".toByteArray())))
            assertThat(badFormat.status).isEqualTo(500)

            val emoji = create(token, title = "unsupported 😀")
            assertThat(emoji.status).isEqualTo(400)
        }
    }

    @Test
    fun `토큰의 역할별 게시판일 때__글을 작성하면__토큰 작성자로 저장되고 검색 투영이 한 행 생긴다`() {
        // given
        val cases = listOf("ROLE_USER" to "COMMUNICATION", "ROLE_ADMIN" to "ADOPTION", "ROLE_SHELTER" to "PROTECTION")
        val created = ArrayList<Long>()

        // when
        for ((role, board) in cases) {
            val reply = create(accessToken(AUTHOR, role), board = board, headers = FORGED_HEADERS)

            // then
            assertThat(reply.status).`as`(role).isEqualTo(200)
            assertThat(reply.code).isEqualTo(200)
            assertThat(reply.message).isEqualTo("게시글이 생성되었습니다.")
            val data = reply.data
            assertThat(data.fieldNames().asSequence().toSet()).isEqualTo(RESPONSE_FIELDS)
            assertThat(data.get("authorId").asLong()).`as`("The token, not X-User-Id, names the author").isEqualTo(AUTHOR)
            assertThat(data.get("authorNickname").asText()).isEqualTo(NICKNAME)
            assertThat(data.get("boardType").asText()).isEqualTo(board)
            val reread = get("/api/v1/posts/read/${data.get("postId").asLong()}").data
            for (field in listOf("postId", "authorId", "authorNickname", "title", "content", "boardType", "imageUrls")) {
                assertThat(reread.get(field)).`as`("$role $field").isEqualTo(data.get(field))
            }
            created.add(data.get("postId").asLong())
        }
        for (postId in created) {
            assertThat(count("SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id = ?", postId.toString()))
                .`as`("The monolith writes no POST_CREATED outbox row").isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM pawbridge_community.post_search_documents d JOIN pawbridge_community.posts p USING(post_id) " +
                        "WHERE d.post_id = ? AND d.title_hash = md5(p.title) AND d.content_hash = md5(p.content) " +
                        "AND d.analyzer_version = 'nori-9.12.3-v1'",
                    postId,
                ),
            ).`as`("The search projection of $postId").isOne()
        }
        assertThat(ContractFakeS3.requests()).isEmpty()
    }

    @Test
    fun `이미지와 영상과 빈 파일일 때__글을 작성하면__빈 파일은 건너뛰고 SDK가 바이트와 MIME을 그대로 올린다`() {
        // given
        val files = listOf(
            Upload("empty.jpg", "image/jpeg", ByteArray(0)),
            Upload("dog.jpg", "image/jpeg", "F3-image".toByteArray()),
            Upload("dog.mp4", "video/mp4", "F3-video".toByteArray()),
        )

        // when
        val reply = create(accessToken(AUTHOR), files = files, headers = FORGED_HEADERS)

        // then
        assertThat(reply.status).isEqualTo(200)
        val puts = ContractFakeS3.requests().filter { it.method == "PUT" && it.status == 200 }
        assertThat(puts).`as`("The empty file is skipped").hasSize(2)
        assertThat(ContractFakeS3.requestCount("DELETE")).isZero()
        val stored = ContractFakeS3.objects()
        for ((mime, content) in listOf("image/jpeg" to "F3-image", "video/mp4" to "F3-video")) {
            val matching = stored.values.filter { it.contentType == mime }
            assertThat(matching).`as`(mime).hasSize(1)
            assertThat(matching.single().sha256).isEqualTo(ContractFakeS3.sha256(content.toByteArray()))
        }
        assertThat(stored.keys).allMatch { it.startsWith(ContractFakeS3.OBJECT_PREFIX) }
        val urls = get("/api/v1/posts/read/${reply.data.get("postId").asLong()}").data.get("imageUrls").map { it.asText() }
        assertThat(urls).`as`("URLs follow the upload order").isEqualTo(puts.map { ContractFakeS3.publicUrl(it.key!!) })
    }

    @Test
    fun `검색 투영 쓰기 권한이 없을 때__글을 작성하면__500이고 행은 롤백되지만 올린 객체는 남고 재시도나 삭제는 없다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        val failed = ContractPostgres.withRevoked("INSERT", "pawbridge_community.post_search_documents") {
            create(accessToken(AUTHOR), files = listOf(Upload("rollback.jpg", "image/jpeg", "rollback-object".toByteArray())))
        }

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(ContractPostgres.snapshot() == before).`as`("The post row rolled back with the projection").isTrue()
        assertThat(ContractFakeS3.requestCount("PUT")).`as`("One upload and no automatic retry").isOne()
        assertThat(ContractFakeS3.objects()).`as`("The uploaded object stays").hasSize(1)
        assertThat(ContractFakeS3.requestCount("DELETE")).isZero()
        assertThat(create(accessToken(AUTHOR)).status).`as`("The next request recovers").isEqualTo(200)
    }

    @Test
    fun `두 번째 업로드가 거부될 때__글을 작성하면__500이고 첫 객체는 남으며 DB는 그대로다`() {
        // given
        val before = ContractPostgres.snapshot()
        ContractFakeS3.failNextPut(after = 1, count = 1)

        // when
        val reply = create(
            accessToken(AUTHOR),
            files = listOf(Upload("one.jpg", "image/jpeg", "one".toByteArray()), Upload("two.jpg", "image/jpeg", "two".toByteArray())),
        )

        // then
        assertThat(reply.status).isEqualTo(500)
        assertThat(ContractFakeS3.requestCount("PUT")).isEqualTo(2)
        assertThat(ContractFakeS3.objects()).`as`("The first object stays").hasSize(1)
        assertThat(ContractFakeS3.requestCount("DELETE")).isZero()
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `닉네임을 읽을 수 없을 때__글을 작성하면__대체 이름으로 만들어지고 복구되면 실제 닉네임이다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        val fallback = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") { create(token) }
        val recovered = create(token)

        // then
        assertThat(fallback.status).isEqualTo(200)
        assertThat(fallback.data.get("authorNickname").asText()).isEqualTo("사용자$AUTHOR")
        assertThat(recovered.data.get("authorNickname").asText()).isEqualTo(NICKNAME)
    }

    @Test
    @Tag("timezone-sensitive")
    fun `저장된 글일 때__단건을 조회하면__날짜와 이미지 목록을 JVM 시간대로 그대로 준다`() {
        // given
        seedPost(
            AUTHOR, AUTHOR, imageUrls = "[\"https://fixture.invalid/가.jpg\",null,\"https://fixture.invalid/가.jpg\"]",
            createdAt = "2026-10-17 15:01:02.123456", updatedAt = "2026-10-17 16:02:00",
        )

        // when
        val reply = assertNoSideEffects { get("/api/v1/posts/read/$AUTHOR", "invalid") }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
        assertThat(reply.data.get("authorNickname").asText()).isEqualTo(NICKNAME)
        assertThat(reply.data.get("imageUrls").map { if (it.isNull) null else it.asText() })
            .isEqualTo(listOf("https://fixture.invalid/가.jpg", null, "https://fixture.invalid/가.jpg"))
        assertThat(reply.data.get("createdAt").asText()).isEqualTo(shownInJvmZone("2026-10-17 15:01:02.123456"))
        assertThat(reply.data.get("updatedAt").asText()).isEqualTo(shownInJvmZone("2026-10-17 16:02:00"))
    }

    @Test
    fun `없거나 잘못된 경로일 때__단건을 조회하면__정해진 상태로 거부되고 쓰기는 없다`() {
        // given
        seedPost(AUTHOR, AUTHOR)

        // when
        // then
        assertNoSideEffects {
            for (missing in listOf("999999999", "0", "-1")) {
                assertThat(get("/api/v1/posts/read/$missing").status).`as`(missing).isEqualTo(404)
            }
            for (invalid in listOf("abc", "9223372036854775808")) {
                assertThat(get("/api/v1/posts/read/$invalid").status).`as`(invalid).isEqualTo(400)
            }
            for ((method, path) in listOf(
                "GET" to "/error",
                "HEAD" to "/api/v1/posts/read/$AUTHOR",
                "OPTIONS" to "/api/v1/posts/read/$AUTHOR",
                "POST" to "/api/v1/posts/read/$AUTHOR",
            )) {
                assertThat(call(method, path).status).`as`("$method $path").isEqualTo(403)
            }
            val unauthenticated = create(null)
            assertThat(unauthenticated.status).`as`("An earlier authenticated request cannot populate this one").isEqualTo(401)
        }
    }

    @Test
    fun `내부 사용자 경로나 옛 별칭일 때__요청하면__거부한다`() {
        // given
        seedPost(AUTHOR, AUTHOR)

        // when
        // then
        assertNoSideEffects {
            for (path in listOf("/api/v1/users/internal/42/nickname", "/api/users/internal/42/nickname", "/api/posts/read/$AUTHOR")) {
                assertThat(get(path).status).`as`(path).isEqualTo(403)
            }
            assertThat(get("/api/v1/posts/read/$AUTHOR").status).isEqualTo(200)
        }
    }

    private fun create(
        token: String?,
        title: String = "한글 강아지 입양",
        board: String = "COMMUNICATION",
        files: List<Upload> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): Reply {
        val fields = mapOf("title" to title, "content" to "보호소 반려동물 이야기", "boardType" to board)
        return multipart("POST", "/api/v1/posts", fields, files, token, headers)
    }

    private companion object {
        const val AUTHOR = 9_903_042L
        const val NICKNAME = "합성작성자"
        val FORGED_HEADERS = mapOf("X-User-Id" to "999999", "X-User-Role" to "ROLE_FORGED")
        val RESPONSE_FIELDS = setOf(
            "postId", "authorId", "authorNickname", "title", "content", "boardType", "imageUrls", "createdAt", "updatedAt",
        )
    }
}
