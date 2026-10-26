package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat

/**
 * Helpers shared by the post contracts (create, delete, update, list, search): creating a post through the API,
 * the exact set of fields of a post response and the stored rows of a post.
 */
abstract class PostContractTestBase : ApiContractTestBase() {
    /** Creates a post through the public API and returns its data node; fails the test when the API refuses. */
    protected fun createPost(
        token: String,
        title: String = "Contract title",
        content: String = "Contract content",
        board: String = "COMMUNICATION",
        files: List<Upload> = emptyList(),
    ): JsonNode {
        val reply = multipart(
            "POST",
            "/api/v1/posts",
            mapOf("title" to title, "content" to content, "boardType" to board),
            files,
            token,
        )
        assertThat(reply.status).`as`("Creating the post through the API").isEqualTo(200)
        return reply.data
    }

    protected fun readPost(postId: Long): Reply = get("/api/v1/posts/read/$postId")

    protected fun deletePost(postId: Any, token: String?, headers: Map<String, String> = emptyMap()): Reply =
        call("DELETE", "/api/v1/posts/$postId", token, headers)

    /** The stored post row as JSON. */
    protected fun postRow(postId: Long): JsonNode = row("pawbridge_community.posts", "post_id", postId)

    /** Posts, their outbox rows and search projection rows, comparable as one value. */
    protected fun postBusinessState(postId: Long): List<String?> = listOf(
        rowJson("pawbridge_community.posts", "post_id", postId),
        scalar(
            "SELECT COALESCE(json_agg(e ORDER BY outbox_id), '[]'::json)::text FROM pawbridge_community.outbox_events e " +
                "WHERE aggregate_id = ?",
            postId.toString(),
        ),
        scalar(
            "SELECT COALESCE(json_agg(d), '[]'::json)::text FROM pawbridge_community.post_search_documents d WHERE post_id = ?",
            postId,
        ),
    )

    protected fun searchDocumentCount(postId: Long): Long =
        count("SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id = ?", postId)

    protected fun outboxCount(postId: Long): Long =
        count("SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id = ?", postId.toString())

    protected fun storageKeyOf(url: JsonNode): String = ContractFakeS3.keyOf(url.asText()) ?: error("Not a storage URL: $url")
}
