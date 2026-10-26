package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The public comment list of a post (replaces the packaged F8 smoke). */
class CommentReadContractTest : CommentContractTestBase() {
    @BeforeEach
    fun seedComments() {
        seedUser(AUTHOR, "댓글 합성작성자")
        seedPost(POST, AUTHOR)
        seedPost(DELETED_POST, AUTHOR, deletedAt = "2025-01-03 01:02:03")
        seedComment(OLD, POST, AUTHOR, "F8 오래된 댓글", "2025-01-01 01:02:03.123456", "2025-01-02 01:02:03.123456")
        seedComment(NEWEST_A, POST, MISSING_AUTHOR, "F8 최신 댓글 A", "2025-01-03 01:02:03.654321", "2025-01-04 01:02:03.654321")
        seedComment(NEWEST_B, POST, AUTHOR, "F8 최신 댓글 B", "2025-01-03 01:02:03.654321", "2025-01-04 01:02:03.654321")
        seedComment(REMOVED, POST, AUTHOR, "F8 삭제 댓글", "2025-01-05 01:02:03", "2025-01-05 01:02:03", "2025-01-06 01:02:03")
        seedComment(OF_DELETED_POST, DELETED_POST, AUTHOR, "F8 삭제 부모의 댓글", "2025-01-01 01:02:03", "2025-01-02 01:02:03")
        seedComment(OF_MISSING_POST, MISSING_POST, AUTHOR, "F8 없는 부모의 댓글", "2025-01-01 01:02:03", "2025-01-02 01:02:03")
    }

    @Test
    fun `삭제된 댓글이 섞여 있을 때__목록을 조회하면__최신순이고 삭제 댓글과 없는 작성자 처리가 맞다`() {
        // given
        // when
        val rows = comments(POST)

        // then
        val ids = rows.map { it.get("commentId").asLong() }
        assertThat(ids.toSet()).isEqualTo(setOf(OLD, NEWEST_A, NEWEST_B))
        assertThat(ids.take(2).toSet()).isEqualTo(setOf(NEWEST_A, NEWEST_B))
        assertThat(ids.last()).isEqualTo(OLD)
        val known = byId(rows).getValue(OLD)
        assertThat(known.get("authorId").asLong()).isEqualTo(AUTHOR)
        assertThat(known.get("authorNickname").asText()).isEqualTo("댓글 합성작성자")
        assertThat(known.get("content").asText()).isEqualTo("F8 오래된 댓글")
        assertThat(known.get("createdAt")).isNotEqualTo(known.get("updatedAt"))
        assertThat(byId(rows).getValue(NEWEST_A).get("authorNickname").asText()).`as`("A missing author falls back per item")
            .isEqualTo("사용자$MISSING_AUTHOR")
    }

    @Test
    @Tag("timezone-sensitive")
    fun `저장된 댓글일 때__목록을 조회하면__날짜를 JVM 시간대로 보여준다`() {
        // given
        // when
        val known = byId(comments(POST)).getValue(OLD)

        // then
        assertThat(known.get("createdAt").asText()).isEqualTo(shownInJvmZone("2025-01-01 01:02:03.123456"))
        assertThat(known.get("updatedAt").asText()).isEqualTo(shownInJvmZone("2025-01-02 01:02:03.123456"))
    }

    @Test
    fun `위조된 신원과 쿼리일 때__목록을 조회하면__공개 목록이 그대로다`() {
        // given
        val plain = comments(POST)

        // when
        val forged = comments(
            POST,
            mapOf("Authorization" to "Bearer invalid", "X-User-Id" to "999", "X-User-Role" to "ROLE_ADMIN"),
            "?page=999&size=1&authorId=999",
        )

        // then
        assertThat(byId(forged)).isEqualTo(byId(plain))
    }

    @Test
    fun `댓글이 없거나 부모가 삭제됐거나 없을 때__목록을 조회하면__부모를 검증하지 않고 있는 댓글을 준다`() {
        // given
        // when
        // then
        assertThat(comments(NO_COMMENTS_POST)).`as`("A post without comments").isEmpty()
        assertThat(comments(DELETED_POST).map { it.get("commentId").asLong() }).`as`("Parent soft deletion filters nothing")
            .isEqualTo(listOf(OF_DELETED_POST))
        assertThat(comments(MISSING_POST).map { it.get("commentId").asLong() }).`as`("The parent need not exist")
            .isEqualTo(listOf(OF_MISSING_POST))
    }

    @Test
    fun `닉네임을 읽을 수 없을 때__목록을 조회하면__댓글마다 대체 이름이고 복구되면 같은 풀에서 돌아온다`() {
        // given
        val before = byId(comments(POST))

        // when
        val fallback = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") { comments(POST) }
        val recovered = byId(comments(POST))

        // then
        for (row in fallback) {
            assertThat(row.get("authorNickname").asText()).isEqualTo("사용자" + row.get("authorId").asLong())
        }
        assertThat(recovered).isEqualTo(before)
    }

    @Test
    fun `댓글 내용 열 권한이 없을 때__목록을 조회하면__안전한 500이고 복구되면 다시 조회된다`() {
        // given
        val before = byId(comments(POST))

        // when
        val failed = ContractPostgres.withRevoked("SELECT(content)", "pawbridge_community.comments") {
            assertNoSideEffects { get("/api/v1/comments/posts/read/$POST") }
        }

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(failed.text).isEqualTo("""{"code":500,"message":"서버 에러!","data":null}""")
        assertThat(byId(comments(POST))).isEqualTo(before)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "POST, /api/v1/comments/posts/read/9908001, 403",
        "PUT, /api/v1/comments/posts/read/9908001, 403",
        "DELETE, /api/v1/comments/posts/read/9908001, 403",
        "HEAD, /api/v1/comments/posts/read/9908001, 403",
        "GET, /api/v1/comments/posts/read, 403",
        "GET, /api/v1/comments/posts/read/9908001/extra, 403",
        "GET, /api/v1/comments/posts/9908001, 403",
        "POST, /api/v1/comments/posts/9908001, 401",
        "PUT, /api/v1/comments/9908011, 401",
        "DELETE, /api/v1/comments/9908011, 401",
    )
    fun `인접 경로일 때__인증 없이 요청하면__정해진 상태로 거부되고 아무것도 바뀌지 않는다`(method: String, path: String, status: Int) {
        // given
        // when
        val reply = assertNoSideEffects { call(method, path) }

        // then
        assertThat(reply.status).isEqualTo(status)
    }

    private companion object {
        const val AUTHOR = 9_908_042L
        const val MISSING_AUTHOR = 9_908_099L
        const val POST = 9_908_001L
        const val DELETED_POST = 9_908_002L
        const val MISSING_POST = 9_908_003L
        const val NO_COMMENTS_POST = 9_908_099L
        const val OLD = 9_908_011L
        const val NEWEST_A = 9_908_012L
        const val NEWEST_B = 9_908_013L
        const val REMOVED = 9_908_014L
        const val OF_DELETED_POST = 9_908_021L
        const val OF_MISSING_POST = 9_908_031L
    }
}
