package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Updating a comment as its author, including the no-write path for unchanged content (replaces the F11 smoke). */
class CommentUpdateContractTest : CommentContractTestBase() {
    @BeforeEach
    fun seedParents() {
        seedUser(AUTHOR, NICKNAME)
        seedPost(POST, FOREIGN_AUTHOR)
        seedPost(POST + 1, FOREIGN_AUTHOR)
        seedPost(POST + 2, FOREIGN_AUTHOR)
        seedComment(FOREIGN_COMMENT, POST, FOREIGN_AUTHOR, "F11 다른 작성자 댓글")
        seedComment(DELETED_COMMENT, POST, AUTHOR, "F11 이미 삭제 댓글", deletedAt = "2025-01-03 01:02:03")
    }

    @Test
    fun `거부되는 토큰일 때__댓글을 수정하면__401이고 본문이나 경로를 보기 전에 거부된다`() {
        // given
        val comment = ownComment()

        // when
        // then
        assertNoSideEffects {
            for ((name, token) in rejectedTokens(AUTHOR) + ("none" to null)) {
                val reply = updateComment(comment, body("F11 변경 본문"), token, FORGED)
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.code).isEqualTo(401)
                assertThat(reply.data.isNull).isTrue()
            }
            assertThat(updateComment("not-a-long", """{"content":""", null, FORGED).status).isEqualTo(401)
        }
    }

    @Test
    fun `다른 작성자의 댓글일 때__역할에 상관없이 수정하면__403이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        // then
        assertNoSideEffects {
            for (role in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER")) {
                val reply = updateComment(FOREIGN_COMMENT, body("F11 다른 작성자 댓글"), accessToken(AUTHOR, role), FORGED)
                assertThat(reply.status).`as`(role).isEqualTo(403)
                assertThat(reply.message).isEqualTo("댓글에 접근할 권한이 없습니다.")
            }
        }
    }

    @Test
    fun `없거나 삭제된 댓글이거나 경로가 잘못됐을 때__수정하면__404 또는 400이고 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        assertNoSideEffects {
            for (absent in listOf(9_911_999L, 0L, -1L, DELETED_COMMENT)) {
                val reply = updateComment(absent, body("F11 변경 본문"), token, FORGED)
                assertThat(reply.status).`as`("$absent").isEqualTo(404)
                assertThat(reply.message).isEqualTo("댓글을 찾을 수 없습니다.")
            }
            for (malformed in listOf("not-a-long", "9223372036854775808")) {
                val reply = updateComment(malformed, body("F11 변경 본문"), token, FORGED)
                assertThat(reply.status).`as`(malformed).isEqualTo(400)
                assertThat(reply.message).isEqualTo("요청 경로의 식별자 형식이 올바르지 않습니다.")
            }
        }
    }

    @Test
    fun `내용이 비었거나 본문이 깨졌을 때__수정하면__400이고 없는 댓글이어도 검증이 먼저다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment()

        // when
        // then
        assertNoSideEffects {
            for (raw in listOf("{}", """{"content":null}""", """{"content":""}""", """{"content":" \t\r\n\f"}""")) {
                val reply = updateComment(comment, raw, token, FORGED)
                assertThat(reply.status).`as`(raw).isEqualTo(400)
                assertThat(reply.message).isEqualTo("content: 내용은 필수입니다")
            }
            for (raw in listOf("""{"content":""", "", "null")) {
                val reply = updateComment(comment, raw, token, FORGED)
                assertThat(reply.status).`as`("[$raw]").isEqualTo(400)
                assertThat(reply.message).isEqualTo("요청 본문 형식이 올바르지 않습니다.")
            }
            val absent = updateComment(9_911_999L, "{}", token, FORGED)
            assertThat(absent.status).isEqualTo(400)
            assertThat(absent.message).isEqualTo("content: 내용은 필수입니다")
        }
    }

    @Test
    fun `지원하지 않는 문자일 때__수정하면__이모지는 400 안내이고 NUL은 안전한 500이며 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment()

        // when
        // then
        assertNoSideEffects {
            val emoji = updateComment(comment, body("$PRIVATE_BODY 😀 문자"), token, FORGED)
            assertThat(emoji.status).isEqualTo(400)
            assertThat(emoji.message).isEqualTo("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
            val nul = updateComment(comment, body("$PRIVATE_BODY \u0000 NUL"), token, FORGED)
            assertThat(nul.status).isEqualTo(500)
            assertThat(nul.message).isEqualTo("서버 에러!")
        }
    }

    @Test
    @Tag("timezone-sensitive")
    fun `작성자일 때__역할과 내용을 바꿔 수정하면__내용과 수정 시각만 바뀌고 응답은 수정 전 시각이다`() {
        // given
        val comment = ownComment()

        // when
        // then
        for ((index, role) in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER").withIndex()) {
            changed(comment, " F11 원문 앞뒤 공백\n보존 $index ", accessToken(AUTHOR, role))
        }
        for (content in listOf(" ", "　", "F11 긴 내용 " + "가".repeat(12000))) {
            changed(comment, content, accessToken(AUTHOR))
        }
        val current = changed(comment, PRIVATE_BODY, accessToken(AUTHOR)).get("content").asText()
        assertThat(current).isEqualTo(PRIVATE_BODY)
    }

    @Test
    fun `내용이 같을 때__수정하면__닉네임은 읽지만 어떤 행도 바뀌지 않는다`() {
        // given
        val comment = ownComment()
        val token = accessToken(AUTHOR)

        // when
        // then
        unchanged(comment, "F11 최초 본문", token)
    }

    @Test
    fun `작성자 행이 없거나 닉네임이 비었을 때__수정하면__대체 이름 또는 저장된 값을 그대로 보여준다`() {
        // given
        val comment = ownComment()
        val token = accessToken(AUTHOR)

        // when
        execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", AUTHOR)
        unchanged(comment, "F11 최초 본문", token, "사용자$AUTHOR")
        seedUser(AUTHOR, NICKNAME)
        ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") { unchanged(comment, "F11 최초 본문", token, "사용자$AUTHOR") }

        // then
        for ((nickname, shown) in listOf("F11 회복 이름" to "F11 회복 이름", "" to null, "  " to "  ", NICKNAME to NICKNAME)) {
            execute("UPDATE pawbridge_user.users SET nickname = ? WHERE user_id = ?", nickname, AUTHOR)
            unchanged(comment, "F11 최초 본문", token, shown)
        }
    }

    @Test
    fun `부모 글이 삭제됐거나 없고 글 읽기 권한도 없을 때__수정하면__부모를 읽지 않고 수정된다`() {
        // given
        val token = accessToken(AUTHOR)
        seedComment(OF_DELETED_PARENT, POST + 1, AUTHOR, "F11 삭제 부모의 댓글")
        seedComment(OF_ABSENT_PARENT, POST + 2, AUTHOR, "F11 없는 부모의 댓글")
        execute("UPDATE pawbridge_community.posts SET deleted_at = CURRENT_TIMESTAMP WHERE post_id = ?", POST + 1)
        execute("DELETE FROM pawbridge_community.posts WHERE post_id = ?", POST + 2)

        // when
        // then
        ContractPostgres.withRevoked(
            "SELECT(post_id, author_id, title, content, board_type, image_urls, deleted_at, created_at, updated_at)",
            "pawbridge_community.posts",
        ) {
            changed(OF_DELETED_PARENT, "F11 삭제 부모의 댓글 수정", token)
            changed(OF_ABSENT_PARENT, "F11 없는 부모의 댓글 수정", token)
        }
    }

    @Test
    fun `수정 열 권한이 없을 때__수정하면__같은 내용은 쓰기가 없어 성공하고 바뀐 내용은 안전한 500이며 복구된다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment()

        // when
        // then
        ContractPostgres.withRevoked("UPDATE(content, updated_at)", "pawbridge_community.comments") {
            unchanged(comment, "F11 최초 본문", token)
            val (failed, logs) = capturingLogs { assertNoSideEffects { updateComment(comment, body("$PRIVATE_BODY 변경 권한 실패"), token) } }
            assertThat(failed.status).isEqualTo(500)
            assertThat(failed.message).isEqualTo("서버 에러!")
            assertNothingSecretIn(logs, PRIVATE_BODY, token)
            assertThat(assertNoSideEffects { updateComment(comment, body("F11 최초 본문"), null) }.status).isEqualTo(401)
        }
        changed(comment, "F11 변경 권한 회복", token)
    }

    @Test
    fun `조회 열 권한이 없을 때__수정하면__안전한 500이고 복구되면 다시 수정된다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment()

        // when
        // then
        for (privilege in listOf("SELECT(content)", "SELECT(comment_id)")) {
            val failed = ContractPostgres.withRevoked(privilege, "pawbridge_community.comments") {
                assertNoSideEffects { updateComment(comment, body("$PRIVATE_BODY SQL 권한 실패"), token) }
            }
            assertThat(failed.status).`as`(privilege).isEqualTo(500)
            assertThat(failed.message).isEqualTo("서버 에러!")
            changed(comment, "F11 $privilege 회복", token)
        }
    }

    @Test
    fun `수정한 댓글을 삭제한 뒤__다시 수정하면__수정된 내용은 보존되고 404다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment()
        changed(comment, "F11 수정된 본문", token)

        // when
        val deleted = deleteComment(comment, token)
        val again = assertNoSideEffects { updateComment(comment, body("F11 삭제 후 재수정"), token) }

        // then
        assertThat(deleted.status).isEqualTo(200)
        assertThat(commentRow(comment).get("content").asText()).`as`("Deleting keeps the edited content").isEqualTo("F11 수정된 본문")
        assertThat(comments(POST).map { it.get("commentId").asLong() }).doesNotContain(comment)
        assertThat(again.status).isEqualTo(404)
        assertThat(again.message).isEqualTo("댓글을 찾을 수 없습니다.")
    }

    private fun withoutEdited(row: JsonNode): JsonNode = omitting(row, "content", "updated_at")

    private fun ownComment(): Long {
        seedComment(OWN_COMMENT, POST, AUTHOR, "F11 최초 본문")
        return OWN_COMMENT
    }

    private fun body(content: String): String = MAPPER.writeValueAsString(mapOf("content" to content))

    /** The public GET of a comment, which is the time baseline of a PUT response. */
    private fun visible(commentId: Long): JsonNode {
        val postId = commentRow(commentId).get("post_id").asLong()
        return byId(comments(postId)).getValue(commentId)
    }

    /** A PUT with new content: only content and updated_at change, the response shows the stored times from before. */
    private fun changed(commentId: Long, content: String, token: String): JsonNode {
        val before = ContractPostgres.snapshot()
        val storage = storageState()
        val saved = visible(commentId)
        val reply = updateComment(commentId, body(content), token, FORGED)
        val after = ContractPostgres.snapshot()
        assertThat(reply.status).isEqualTo(200)
        val expected = (saved.deepCopy<ObjectNode>()).put("content", content)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("댓글이 수정되었습니다.")
        assertThat(reply.data).isEqualTo(expected)
        val old = commentsOf(before)
        val new = commentsOf(after)
        assertThat(new.keys).isEqualTo(old.keys)
        for ((key, row) in old) {
            if (key != commentId) {
                assertThat(new.getValue(key)).isEqualTo(row)
            }
        }
        val previous = old.getValue(commentId)
        val current = new.getValue(commentId)
        assertThat(current.get("content").asText()).isEqualTo(content)
        assertThat(current.get("updated_at")).isNotEqualTo(previous.get("updated_at"))
        assertThat(withoutEdited(current)).isEqualTo(withoutEdited(previous))
        assertThat(current.get("deleted_at")).isEqualTo(previous.get("deleted_at"))
        assertOnlyCommentsDiffer(before, after)
        assertThat(storageState()).isEqualTo(storage)
        assertNoTransactionOpen()
        val observed = visible(commentId)
        assertThat(observed.get("content").asText()).isEqualTo(content)
        assertThat(observed.get("updatedAt").asText()).isEqualTo(shownInJvmZone(current.get("updated_at").asText().replace('T', ' ')))
        return observed
    }

    /** A PUT with the stored content: the answer is the same shape, and no row anywhere changes. */
    private fun unchanged(commentId: Long, content: String, token: String, nickname: String? = NICKNAME) {
        val before = ContractPostgres.snapshot()
        val saved = visible(commentId)
        assertThat(saved.get("content").asText()).isEqualTo(content)
        val reply = updateComment(commentId, body(content), token, FORGED)
        assertThat(reply.status).isEqualTo(200)
        val expected = saved.deepCopy<ObjectNode>()
        expected.put("content", content)
        if (nickname == null) expected.putNull("authorNickname") else expected.put("authorNickname", nickname)
        assertThat(reply.data).isEqualTo(expected)
        assertThat(ContractPostgres.snapshot() == before).`as`("A same-content PUT writes nothing").isTrue()
        assertNoTransactionOpen()
    }

    private fun assertNothingSecretIn(logs: String, vararg secrets: String) {
        for (secret in listOf(*secrets, ContractPostgres.appPassword, ContractApplicationInitializer.jwtSecret)) {
            assertThat(logs.contains(secret)).`as`("The log must not contain a request body, token or credential").isFalse()
        }
    }

    private companion object {
        const val AUTHOR = 9_911_042L
        const val FOREIGN_AUTHOR = 9_911_043L
        const val POST = 9_911_001L
        const val FOREIGN_COMMENT = 9_911_011L
        const val DELETED_COMMENT = 9_911_012L
        const val OWN_COMMENT = 9_911_100L
        const val OF_DELETED_PARENT = 9_911_101L
        const val OF_ABSENT_PARENT = 9_911_102L
        const val NICKNAME = "F11 합성 댓글작성자"
        const val PRIVATE_BODY = "F11_PRIVATE_BODY_DO_NOT_LOG"
        val FORGED = mapOf("X-User-Id" to "9970999", "X-User-Role" to "ROLE_ADMIN")
    }
}
