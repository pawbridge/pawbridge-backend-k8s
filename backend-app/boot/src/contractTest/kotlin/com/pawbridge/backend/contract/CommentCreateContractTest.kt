package com.pawbridge.backend.contract

import java.time.Duration
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Creating a comment as the authenticated member (replaces the packaged F9 smoke). */
class CommentCreateContractTest : CommentContractTestBase() {
    @BeforeEach
    fun seedParents() {
        seedUser(AUTHOR, NICKNAME)
        seedUser(PARENT_AUTHOR, "댓글 부모 작성자")
        for ((index, board) in listOf("COMMUNICATION", "ADOPTION", "PROTECTION", "MISSING", "REPORT").withIndex()) {
            seedPost(POST + index, PARENT_AUTHOR, board)
        }
        seedPost(POST + 5, PARENT_AUTHOR, deletedAt = "2025-01-03 01:02:03")
    }

    @Test
    fun `거부되는 토큰일 때__댓글을 작성하면__401이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        // then
        assertNoSideEffects {
            for ((name, token) in rejectedTokens(AUTHOR) + ("none" to null)) {
                val reply = createComment(POST, PRIVATE_BODY, token, FORGED)
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.code).isEqualTo(401)
                assertThat(reply.data.isNull).isTrue()
            }
        }
    }

    @Test
    fun `내용이 비었거나 본문이 깨졌을 때__댓글을 작성하면__400이고 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        assertNoSideEffects {
            for (body in listOf("{}", """{"content":null}""", """{"content":""}""", """{"content":" \t\r\n\f"}""")) {
                val reply = postJson("/api/v1/comments/posts/$POST", body, token, FORGED)
                assertThat(reply.status).`as`(body).isEqualTo(400)
                assertThat(reply.message).isEqualTo("content: 내용은 필수입니다")
                assertThat(reply.data.isNull).isTrue()
            }
            val broken = postJson("/api/v1/comments/posts/$POST", """{"content":""", token, FORGED)
            assertThat(broken.status).isEqualTo(400)
            assertThat(broken.message).isEqualTo("요청 본문 형식이 올바르지 않습니다.")
            val absent = call("POST", "/api/v1/comments/posts/$POST", token, FORGED, null, "application/json")
            assertThat(absent.status).isEqualTo(400)
            assertThat(absent.message).isEqualTo("요청 본문 형식이 올바르지 않습니다.")
        }
    }

    @Test
    fun `부모가 없거나 삭제됐거나 경로가 잘못됐을 때__댓글을 작성하면__정해진 상태로 거부되고 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        assertNoSideEffects {
            for (parent in listOf(POST + 5, 9_909_999L, 0L, -1L)) {
                val reply = createComment(parent, "F9 부모 부재", token, FORGED)
                assertThat(reply.status).`as`("$parent").isEqualTo(404)
                assertThat(reply.message).isEqualTo("게시글을 찾을 수 없습니다.")
                assertThat(reply.data.isNull).isTrue()
            }
            for (invalid in listOf("not-a-long", "9223372036854775808")) {
                val reply = createComment(invalid, "F9 잘못된 경로", token, FORGED)
                assertThat(reply.status).`as`(invalid).isEqualTo(400)
                assertThat(reply.message).isEqualTo("요청 경로의 식별자 형식이 올바르지 않습니다.")
            }
        }
    }

    @Test
    fun `지원하지 않는 문자일 때__댓글을 작성하면__이모지는 400 안내이고 NUL은 안전한 500이며 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        assertNoSideEffects {
            val emoji = createComment(POST, "F9 😀 문자", token, FORGED)
            assertThat(emoji.status).isEqualTo(400)
            assertThat(emoji.message).isEqualTo("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
            val nul = createComment(POST, "F9 \u0000 NUL", token, FORGED)
            assertThat(nul.status).isEqualTo(500)
            assertThat(nul.message).isEqualTo("서버 에러!")
            assertThat(nul.data.isNull).isTrue()
        }
    }

    @Test
    @Tag("timezone-sensitive")
    fun `모든 게시판과 역할일 때__댓글을 작성하면__원문 그대로 한 행만 늘고 바로 공개 목록에 보인다`() {
        // given
        val roles = listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER", "ROLE_USER", "ROLE_USER")
        val contents = listOf(" F9 원문 앞뒤 공백\n보존 ") + (1..4).map { "F9 모든 게시판 $it" }

        // when
        // then
        for (index in roles.indices) {
            created(POST + index, contents[index], accessToken(AUTHOR, roles[index]))
        }
        for (content in listOf(" ", "　", "F9 긴 내용 " + "가".repeat(12000))) {
            created(POST, content, accessToken(AUTHOR))
        }
        val duplicate = created(POST, "F9 같은 입력도 새 댓글", accessToken(AUTHOR))
        assertThat(created(POST, "F9 같은 입력도 새 댓글", accessToken(AUTHOR)).get("commentId"))
            .`as`("Separate POSTs are not idempotent").isNotEqualTo(duplicate.get("commentId"))
    }

    @Test
    fun `작성자 행이 없거나 닉네임을 읽을 수 없을 때__댓글을 작성하면__대체 이름이고 복구되면 실제 닉네임이다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", AUTHOR)
        val missing = created(POST, "F9 없는 회원 fallback", token)
        seedUser(AUTHOR, NICKNAME)
        val unreadable = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            created(POST, "F9 닉네임 권한 fallback", token)
        }
        val recovered = created(POST, "F9 닉네임 회복", token)

        // then
        assertThat(missing.get("authorNickname").asText()).isEqualTo("사용자$AUTHOR")
        assertThat(unreadable.get("authorNickname").asText()).isEqualTo("사용자$AUTHOR")
        assertThat(recovered.get("authorNickname").asText()).isEqualTo(NICKNAME)
    }

    @Test
    fun `삽입 열 권한이 없을 때__댓글을 작성하면__안전한 500이고 본문이 로그에 없으며 복구되면 작성된다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        val (failed, logs) = capturingLogs {
            ContractPostgres.withRevoked("INSERT(content)", "pawbridge_community.comments") {
                val denied = assertNoSideEffects { createComment(POST, PRIVATE_BODY, token) }
                assertNoSideEffects { createComment(POST, "F9 인증 선행", null) }.also { assertThat(it.status).isEqualTo(401) }
                denied
            }
        }
        val recovered = created(POST, "F9 댓글 권한 회복", token)

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(failed.message).isEqualTo("서버 에러!")
        assertThat(failed.data.isNull).isTrue()
        assertThat(recovered.get("content").asText()).isEqualTo("F9 댓글 권한 회복")
        assertNothingSecretIn(logs, PRIVATE_BODY, token)
    }

    @Test
    fun `생성된 식별자를 읽을 수 없을 때__댓글을 작성하면__안전한 500이고 행은 롤백되며 복구되면 작성된다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(comment_id)", "pawbridge_community.comments") {
            assertNoSideEffects { createComment(POST, PRIVATE_BODY, token) }
        }
        val recovered = created(POST, "F9 댓글 권한 회복", token)

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(failed.message).isEqualTo("서버 에러!")
        assertThat(recovered.get("content").asText()).isEqualTo("F9 댓글 권한 회복")
    }

    /** Creates a comment and proves the contract of a success: exactly one new row and nothing else changed. */
    private fun created(postId: Long, content: String, token: String): com.fasterxml.jackson.databind.JsonNode {
        val before = ContractPostgres.snapshot()
        val storage = storageState()
        val reply = createComment(postId, content, token, FORGED)
        val after = ContractPostgres.snapshot()
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("댓글이 생성되었습니다.")
        val item = reply.data
        assertThat(item.fieldNames().asSequence().toSet()).isEqualTo(COMMENT_FIELDS)
        val id = item.get("commentId").asLong()
        assertThat(id).isPositive()
        assertThat(item.get("postId").asLong()).isEqualTo(postId)
        assertThat(item.get("authorId").asLong()).isEqualTo(AUTHOR)
        assertThat(item.get("content").asText()).isEqualTo(content)
        val oldRows = commentsOf(before)
        val newRows = commentsOf(after)
        assertThat(newRows.keys - oldRows.keys).isEqualTo(setOf(id))
        for ((key, old) in oldRows) {
            assertThat(newRows.getValue(key)).`as`("Old comment $key").isEqualTo(old)
        }
        val stored = newRows.getValue(id)
        assertThat(stored.get("post_id").asLong()).isEqualTo(postId)
        assertThat(stored.get("author_id").asLong()).isEqualTo(AUTHOR)
        assertThat(stored.get("content").asText()).isEqualTo(content)
        assertThat(stored.get("deleted_at").isNull).isTrue()
        assertOnlyCommentsDiffer(before, after)
        assertThat(storageState()).isEqualTo(storage)
        assertNoTransactionOpen()
        val fetched = byId(comments(postId)).getValue(id)
        for (field in COMMENT_FIELDS - setOf("createdAt", "updatedAt")) {
            assertThat(fetched.get(field)).`as`(field).isEqualTo(item.get(field))
        }
        for (field in listOf("createdAt", "updatedAt")) {
            val delta = Duration.between(LocalDateTime.parse(fetched.get(field).asText()), LocalDateTime.parse(item.get(field).asText())).abs()
            assertThat(delta).`as`("$field of the response and the stored TIMESTAMP(6)").isLessThanOrEqualTo(Duration.ofNanos(1_000))
        }
        return item
    }

    private fun assertNothingSecretIn(logs: String, vararg secrets: String) {
        val forbidden = listOf(*secrets, ContractPostgres.appPassword, ContractApplicationInitializer.jwtSecret)
        for (secret in forbidden) {
            assertThat(logs.contains(secret)).`as`("The log must not contain a request body, token or credential").isFalse()
        }
    }

    private companion object {
        const val AUTHOR = 9_909_042L
        const val PARENT_AUTHOR = 9_909_043L
        const val POST = 9_909_001L
        const val NICKNAME = "F9 합성 댓글작성자"
        const val PRIVATE_BODY = "F9_PRIVATE_BODY_DO_NOT_LOG"
        val FORGED = mapOf("X-User-Id" to "9930999", "X-User-Role" to "ROLE_ADMIN")
    }
}
