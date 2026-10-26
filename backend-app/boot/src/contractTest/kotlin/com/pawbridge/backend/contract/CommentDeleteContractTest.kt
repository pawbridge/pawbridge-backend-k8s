package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Soft deleting a comment as its author (replaces the packaged F10 smoke). */
class CommentDeleteContractTest : CommentContractTestBase() {
    @BeforeEach
    fun seedParents() {
        seedUser(AUTHOR, NICKNAME)
        seedPost(POST, FOREIGN_AUTHOR)
        seedPost(POST + 1, FOREIGN_AUTHOR)
        seedPost(POST + 2, FOREIGN_AUTHOR)
        seedComment(FOREIGN_COMMENT, POST, FOREIGN_AUTHOR, "F10 다른 작성자 댓글")
    }

    @Test
    fun `거부되는 토큰일 때__댓글을 삭제하면__401이고 아무것도 바뀌지 않는다`() {
        // given
        val comment = ownComment(POST)

        // when
        // then
        assertNoSideEffects {
            for ((name, token) in rejectedTokens(AUTHOR) + ("none" to null)) {
                val reply = deleteComment(comment, token, null, FORGED)
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.code).isEqualTo(401)
                assertThat(reply.data.isNull).isTrue()
            }
        }
    }

    @Test
    fun `다른 작성자의 댓글일 때__관리자가 삭제하면__403이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        val reply = assertNoSideEffects { deleteComment(FOREIGN_COMMENT, accessToken(AUTHOR, "ROLE_ADMIN"), null, FORGED) }

        // then
        assertThat(reply.status).isEqualTo(403)
        assertThat(reply.message).isEqualTo("댓글에 접근할 권한이 없습니다.")
        assertThat(reply.data.isNull).isTrue()
    }

    @Test
    fun `없는 댓글이거나 경로가 잘못됐을 때__삭제하면__404 또는 400이고 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        assertNoSideEffects {
            for (absent in listOf(9_910_999L, 0L, -1L)) {
                val reply = deleteComment(absent, token, null, FORGED)
                assertThat(reply.status).`as`("$absent").isEqualTo(404)
                assertThat(reply.message).isEqualTo("댓글을 찾을 수 없습니다.")
                assertThat(reply.data.isNull).isTrue()
            }
            for (malformed in listOf("not-a-long", "9223372036854775808")) {
                val reply = deleteComment(malformed, token, null, FORGED)
                assertThat(reply.status).`as`(malformed).isEqualTo(400)
                assertThat(reply.message).isEqualTo("요청 경로의 식별자 형식이 올바르지 않습니다.")
            }
        }
    }

    @Test
    fun `깨진 본문이 함께 올 때__작성자가 삭제하면__본문은 무시되고 두 날짜만 바뀌며 다시 삭제하면 404다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment(POST)
        seedComment(COMMENT_NEIGHBOR, POST, AUTHOR, "F10 이웃 댓글")

        // when
        val (reply, before, after) = deletedOnce(comment, token, """{"ignored-invalid-json":""")
        val repeated = assertNoSideEffects { deleteComment(comment, token) }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.text).isEqualTo("""{"code":200,"message":"댓글이 삭제되었습니다.","data":null}""")
        assertOnlyDeletionDatesChanged(comment, before, after)
        assertThat(comments(POST).map { it.get("commentId").asLong() }).`as`("The deleted comment is hidden publicly")
            .doesNotContain(comment)
        assertThat(repeated.status).isEqualTo(404)
        assertThat(repeated.message).isEqualTo("댓글을 찾을 수 없습니다.")
    }

    @Test
    fun `관리자나 보호소 토큰과 이모지 NUL 본문일 때__작성자가 삭제하면__본문을 읽지 않고 삭제된다`() {
        // given
        // when
        // then
        for (role in listOf("ROLE_ADMIN", "ROLE_SHELTER")) {
            val comment = ownComment(POST)
            val (reply, before, after) = deletedOnce(comment, accessToken(AUTHOR, role), "{\"content\":\"😀\u0000\"}")
            assertThat(reply.status).`as`(role).isEqualTo(200)
            assertOnlyDeletionDatesChanged(comment, before, after)
        }
    }

    @Test
    fun `부모 글이 삭제됐거나 없을 때__작성자가 삭제하면__부모를 읽지 않고 삭제된다`() {
        // given
        val token = accessToken(AUTHOR)
        val ofDeletedParent = ownComment(POST + 1)
        val ofAbsentParent = ownComment(POST + 2)
        execute("UPDATE pawbridge_community.posts SET deleted_at = CURRENT_TIMESTAMP WHERE post_id = ?", POST + 1)
        execute("DELETE FROM pawbridge_community.posts WHERE post_id = ?", POST + 2)

        // when
        // then
        for (comment in listOf(ofDeletedParent, ofAbsentParent)) {
            val (reply, before, after) = deletedOnce(comment, token, null)
            assertThat(reply.status).isEqualTo(200)
            assertOnlyDeletionDatesChanged(comment, before, after)
        }
    }

    @Test
    fun `회원 행이 없고 닉네임과 글 읽기 권한이 없을 때__작성자가 삭제하면__그것들에 의존하지 않고 삭제된다`() {
        // given
        val token = accessToken(AUTHOR)
        val comment = ownComment(POST)
        execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", AUTHOR)

        // when
        val (reply, before, after) = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            ContractPostgres.withRevoked(
                "SELECT(post_id, author_id, title, content, board_type, image_urls, deleted_at, created_at, updated_at)",
                "pawbridge_community.posts",
            ) { deletedOnce(comment, token, null) }
        }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertOnlyDeletionDatesChanged(comment, before, after)
    }

    @Test
    fun `삭제에 필요한 열 권한이 하나 없을 때__삭제하면__안전한 500이고 행은 그대로이며 복구되면 삭제된다`() {
        // given
        val token = accessToken(AUTHOR)

        // when
        // then
        for ((privilege, target) in listOf(
            "SELECT(author_id)" to "pawbridge_community.comments",
            "SELECT(deleted_at)" to "pawbridge_community.comments",
            "UPDATE(deleted_at)" to "pawbridge_community.comments",
            "UPDATE(updated_at)" to "pawbridge_community.comments",
        )) {
            val comment = ownComment(POST)
            val (denied, unauthenticated) = ContractPostgres.withRevoked(privilege, target) {
                assertNoSideEffects { deleteComment(comment, token) } to assertNoSideEffects { deleteComment(comment, null) }
            }
            assertThat(denied.status).`as`(privilege).isEqualTo(500)
            assertThat(denied.message).isEqualTo("서버 에러!")
            assertThat(unauthenticated.status).`as`("Authentication still comes first").isEqualTo(401)
            val (recovered, before, after) = deletedOnce(comment, token, null)
            assertThat(recovered.status).`as`("$privilege recovered").isEqualTo(200)
            assertOnlyDeletionDatesChanged(comment, before, after)
        }
    }

    private fun ownComment(postId: Long): Long {
        val id = nextComment++
        seedComment(id, postId, AUTHOR, "F10_PRIVATE_BODY_DO_NOT_LOG")
        return id
    }

    private fun deletedOnce(commentId: Long, token: String, body: String?): Triple<Reply, Map<String, String>, Map<String, String>> {
        val before = ContractPostgres.snapshot()
        val storage = storageState()
        val reply = deleteComment(commentId, token, body, FORGED)
        val after = ContractPostgres.snapshot()
        assertThat(storageState()).isEqualTo(storage)
        assertNoTransactionOpen()
        return Triple(reply, before, after)
    }

    private fun assertOnlyDeletionDatesChanged(commentId: Long, before: Map<String, String>, after: Map<String, String>) {
        val old = commentsOf(before)
        val new = commentsOf(after)
        assertThat(new.keys).`as`("No comment is physically removed").isEqualTo(old.keys)
        for ((key, row) in old) {
            if (key != commentId) {
                assertThat(new.getValue(key)).`as`("Comment $key").isEqualTo(row)
            }
        }
        val previous = old.getValue(commentId)
        val current = new.getValue(commentId)
        assertThat(previous.get("deleted_at").isNull).isTrue()
        assertThat(current.get("deleted_at").isNull).isFalse()
        assertThat(current.get("updated_at")).isNotEqualTo(previous.get("updated_at"))
        assertThat(without(current)).isEqualTo(without(previous))
        assertOnlyCommentsDiffer(before, after)
    }

    private fun without(row: JsonNode): JsonNode = omitting(row, "deleted_at", "updated_at")

    private var nextComment = COMMENT_BASE

    private companion object {
        const val AUTHOR = 9_910_042L
        const val FOREIGN_AUTHOR = 9_910_043L
        const val POST = 9_910_001L
        const val FOREIGN_COMMENT = 9_910_011L
        const val COMMENT_NEIGHBOR = 9_910_012L
        const val COMMENT_BASE = 9_910_100L
        const val NICKNAME = "F10 합성 댓글작성자"
        val FORGED = mapOf("X-User-Id" to "9950999", "X-User-Role" to "ROLE_ADMIN")
    }
}
