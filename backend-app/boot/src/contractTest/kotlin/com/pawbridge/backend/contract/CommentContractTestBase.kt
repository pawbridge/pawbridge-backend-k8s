package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat

/** Helpers shared by the comment contracts: the public list, the seven-field comment and the stored row. */
abstract class CommentContractTestBase : ApiContractTestBase() {
    /** One public comment list GET: reads only, never touches the storage, has the envelope and seven fields per item. */
    protected fun comments(postId: Long, headers: Map<String, String> = emptyMap(), extra: String = ""): List<JsonNode> {
        val reply = assertNoSideEffects { get("/api/v1/comments/posts/read/$postId$extra", headers = headers) }
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
        assertThat(reply.data.isArray).isTrue()
        for (item in reply.data) {
            assertThat(item.fieldNames().asSequence().toSet()).isEqualTo(COMMENT_FIELDS)
            assertThat(item.get("postId").asLong()).isEqualTo(postId)
        }
        return reply.data.toList()
    }

    protected fun byId(rows: List<JsonNode>): Map<Long, JsonNode> = rows.associateBy { it.get("commentId").asLong() }

    protected fun commentRow(commentId: Long): JsonNode = row("pawbridge_community.comments", "comment_id", commentId)

    protected fun createComment(
        postId: Any,
        content: String,
        token: String?,
        headers: Map<String, String> = emptyMap(),
    ): Reply = postJson("/api/v1/comments/posts/$postId", MAPPER.writeValueAsString(mapOf("content" to content)), token, headers)

    protected fun updateComment(commentId: Any, body: String, token: String?, headers: Map<String, String> = emptyMap()): Reply =
        putJson("/api/v1/comments/$commentId", body, token, headers)

    protected fun deleteComment(commentId: Any, token: String?, body: String? = null, headers: Map<String, String> = emptyMap()): Reply =
        call("DELETE", "/api/v1/comments/$commentId", token, headers, body?.toByteArray(Charsets.UTF_8), if (body == null) null else "application/json")

    /** The rows of the comments table in a [ContractPostgres.snapshot] value, by id. */
    protected fun commentsOf(snapshot: Map<String, String>): Map<Long, JsonNode> =
        MAPPER.readTree(snapshot.getValue("pawbridge_community.comments")).associateBy { it.get("comment_id").asLong() }

    /** Every table except the comments is identical in both snapshots. */
    protected fun assertOnlyCommentsDiffer(before: Map<String, String>, after: Map<String, String>) {
        assertThat(after.filterKeys { it != "pawbridge_community.comments" })
            .`as`("No table other than the comments changed")
            .isEqualTo(before.filterKeys { it != "pawbridge_community.comments" })
    }

    companion object {
        val COMMENT_FIELDS = setOf("commentId", "postId", "authorId", "authorNickname", "content", "createdAt", "updatedAt")
    }
}
