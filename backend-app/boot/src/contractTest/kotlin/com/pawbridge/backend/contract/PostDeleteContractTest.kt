package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Soft deleting a post as its owner, with best-effort storage cleanup (replaces the packaged F4 smoke). */
class PostDeleteContractTest : PostContractTestBase() {
    @BeforeEach
    fun seedUsers() {
        seedUser(OWNER, "삭제 작성자")
        seedUser(OTHER, "삭제 타인")
    }

    @Test
    fun `거부되는 토큰일 때__글을 삭제하면__401이고 DB와 저장소는 그대로다`() {
        // given
        val post = createPost(accessToken(OWNER), files = listOf(Upload("one.jpg", "image/jpeg", "F4-image".toByteArray())))
        val postId = post.get("postId").asLong()

        // when
        // then
        assertNoSideEffects {
            for ((name, token) in rejectedTokens(OWNER)) {
                val reply = deletePost(postId, token, mapOf("X-User-Id" to "$OWNER", "X-User-Role" to "ROLE_ADMIN"))
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.code).isEqualTo(401)
                assertThat(reply.data.isNull).isTrue()
            }
            assertThat(deletePost(postId, null, mapOf("X-User-Id" to "$OWNER")).status).isEqualTo(401)
        }
    }

    @Test
    fun `다른 작성자의 글일 때__역할에 상관없이 삭제하면__403이고 DB와 저장소는 그대로다`() {
        // given
        val foreign = createPost(accessToken(OTHER), files = listOf(Upload("one.jpg", "image/jpeg", "F4-foreign".toByteArray())))
        val postId = foreign.get("postId").asLong()

        // when
        // then
        assertNoSideEffects {
            for (role in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER")) {
                val reply = deletePost(postId, accessToken(OWNER, role))
                assertThat(reply.status).`as`(role).isEqualTo(403)
                assertThat(reply.code).isEqualTo(403)
                assertThat(reply.message).isEqualTo("게시글에 접근할 권한이 없습니다.")
                assertThat(reply.data.isNull).isTrue()
            }
        }
    }

    @Test
    fun `없는 글일 때__삭제하면__404이고 아무것도 바뀌지 않는다`() {
        // given
        val token = accessToken(OWNER)

        // when
        // then
        assertNoSideEffects {
            for (missing in listOf(99_999_999L, 0L, -1L)) {
                val reply = deletePost(missing, token)
                assertThat(reply.status).`as`("$missing").isEqualTo(404)
                assertThat(reply.message).isEqualTo("게시글을 찾을 수 없습니다.")
                assertThat(reply.data.isNull).isTrue()
            }
        }
    }

    @Test
    fun `저장 URL이 중복되고 범위 밖이 섞였을 때__작성자가 삭제하면__논리 삭제되고 범위 안 URL만 순서대로 지운다`() {
        // given
        val token = accessToken(OWNER)
        val post = createPost(
            token,
            files = listOf(Upload("one.jpg", "image/jpeg", "F4-image".toByteArray()), Upload("two.mp4", "video/mp4", "F4-video".toByteArray())),
        )
        val postId = post.get("postId").asLong()
        val first = post.get("imageUrls").get(0).asText()
        val second = post.get("imageUrls").get(1).asText()
        val urls = listOf(
            "\"$first\"", "null", "\"$first\"", "\"$second\"", "\"https://outside.invalid/no-object\"",
            "\"${ContractFakeS3.PUBLIC_BASE_URL}/other-run/posts/images/00000000-0000-0000-0000-000000000000.jpg\"",
            "\"${ContractFakeS3.PUBLIC_BASE_URL}/${ContractFakeS3.OBJECT_PREFIX}posts/images/%2e%2e/not-an-object\"",
        )
        execute("UPDATE pawbridge_community.posts SET image_urls = ?::json WHERE post_id = ?", urls.joinToString(",", "[", "]"), postId)
        seedComment(COMMENT, postId, OTHER)
        seedPrivateNote(postId)
        val original = postRow(postId)
        val requestsBefore = ContractFakeS3.requests().size

        // when
        val reply = deletePost(postId, token, mapOf("X-User-Id" to "$OTHER"))

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("게시글이 삭제되었습니다.")
        assertThat(reply.data.isNull).isTrue()
        assertSoftDeleted(postId, original)
        val deletes = ContractFakeS3.requests().drop(requestsBefore)
        assertThat(deletes.map { it.method }).isEqualTo(listOf("DELETE", "DELETE", "DELETE"))
        assertThat(deletes.map { it.key }).isEqualTo(listOf(storageKey(first), storageKey(first), storageKey(second)))
        assertThat(deletes.map { it.status }).isEqualTo(listOf(204, 204, 204))
        assertThat(ContractFakeS3.objects()).isEmpty()
        assertThat(count("SELECT count(*) FROM pawbridge_community.comments WHERE post_id = ?", postId))
            .`as`("Soft deletion keeps the comments").isOne()
        assertThat(
            count(
                "SELECT count(*) FROM pawbridge_community.private_notes n JOIN pawbridge_community.posts p " +
                    "ON n.context_type = 'POST' AND n.context_id = p.post_id WHERE p.post_id = ? AND p.deleted_at IS NULL",
                postId,
            ),
        ).`as`("The deleted post is no active note context").isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_notes WHERE context_id = ?", postId))
            .`as`("The private note stays").isOne()
    }

    @Test
    fun `삭제된 글일 때__다시 삭제하면__404이고 DB와 저장소에 아무 일도 없다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token, files = listOf(Upload("one.jpg", "image/jpeg", "F4-image".toByteArray()))).get("postId").asLong()
        assertThat(deletePost(postId, token).status).isEqualTo(200)

        // when
        val repeated = assertNoSideEffects { deletePost(postId, token) }

        // then
        assertThat(repeated.status).isEqualTo(404)
        assertThat(repeated.message).isEqualTo("게시글을 찾을 수 없습니다.")
    }

    @Test
    fun `어떤 게시판의 글이든__작성자가 삭제하면__논리 삭제되고 검색 문서가 사라지며 공개 조회는 404다`() {
        // given
        val token = accessToken(OWNER)
        val posts = listOf("ADOPTION", "PROTECTION", "MISSING", "REPORT").map { board ->
            val created = createPost(token, board = if (board in setOf("ADOPTION", "PROTECTION")) board else "COMMUNICATION")
            val postId = created.get("postId").asLong()
            execute("UPDATE pawbridge_community.posts SET board_type = ? WHERE post_id = ?", board, postId)
            board to postId
        }

        // when
        // then
        for ((board, postId) in posts) {
            val original = postRow(postId)
            val reply = deletePost(postId, accessToken(OWNER, "ROLE_SHELTER"))
            assertThat(reply.status).`as`(board).isEqualTo(200)
            assertSoftDeleted(postId, original)
        }
    }

    @Test
    fun `저장소 삭제가 한 건 거부될 때__작성자가 삭제하면__다음 URL과 DB는 계속 진행하고 재시도하지 않는다`() {
        // given
        val token = accessToken(OWNER)
        val post = createPost(
            token,
            files = listOf(Upload("keep.jpg", "image/jpeg", "keep".toByteArray()), Upload("remove.jpg", "image/jpeg", "remove".toByteArray())),
        )
        val postId = post.get("postId").asLong()
        val keys = post.get("imageUrls").map { storageKey(it.asText()) }
        val original = postRow(postId)
        val requestsBefore = ContractFakeS3.requests().size
        ContractFakeS3.failNextDelete(after = 0, count = 1)

        // when
        val reply = deletePost(postId, token)

        // then
        assertThat(reply.status).isEqualTo(200)
        assertSoftDeleted(postId, original)
        val deletes = ContractFakeS3.requests().drop(requestsBefore)
        assertThat(deletes.map { it.key }).isEqualTo(keys)
        assertThat(deletes.map { it.status }).isEqualTo(listOf(400, 204))
        assertThat(ContractFakeS3.objects().keys).`as`("Only the refused object stays").isEqualTo(setOf(keys[0]))
    }

    @Test
    fun `검색 문서를 지울 권한이 없을 때__삭제하면__500이고 행은 롤백되며 이미 지운 객체는 되살리지 않고 복구 후 삭제된다`() {
        // given
        val token = accessToken(OWNER)
        val post = createPost(token, files = listOf(Upload("rollback.jpg", "image/jpeg", "irreversible-object".toByteArray())))
        val postId = post.get("postId").asLong()
        val original = postRow(postId)
        val stable = postBusinessState(postId)

        // when
        val failed = ContractPostgres.withRevoked("DELETE", "pawbridge_community.post_search_documents") { deletePost(postId, token) }

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(postBusinessState(postId)).`as`("The post row rolled back").isEqualTo(stable)
        assertThat(ContractFakeS3.requestCount("DELETE")).`as`("One external deletion, never retried").isOne()
        assertThat(ContractFakeS3.objects()).`as`("The deleted object is not restored").isEmpty()
        assertThat(readPost(postId).status).isEqualTo(200)
        assertThat(deletePost(postId, token).status).isEqualTo(200)
        assertSoftDeleted(postId, original)
        assertThat(deletePost(postId, null).status).`as`("An earlier principal does not leak").isEqualTo(401)
    }

    private fun assertSoftDeleted(postId: Long, original: JsonNode) {
        val now = postRow(postId)
        for (field in listOf("post_id", "author_id", "title", "content", "board_type", "image_urls", "created_at")) {
            assertThat(now.get(field)).`as`("Soft deletion keeps $field").isEqualTo(original.get(field))
        }
        assertThat(now.get("deleted_at").isNull).isFalse()
        assertThat(now.get("updated_at").isNull).isFalse()
        assertThat(outboxCount(postId)).`as`("The monolith writes no POST_DELETED outbox row").isZero()
        assertThat(searchDocumentCount(postId)).`as`("The search document is removed").isZero()
        val absent = readPost(postId)
        assertThat(absent.status).isEqualTo(404)
        assertThat(absent.data.isNull).isTrue()
        assertThat(absent.message).isEqualTo("게시글을 찾을 수 없습니다.")
        val before = postBusinessState(postId)
        val storage = storageState()
        assertThat(deletePost(postId, accessToken(OWNER)).status).isEqualTo(404)
        assertThat(postBusinessState(postId)).`as`("Repeating the deletion changes nothing").isEqualTo(before)
        assertThat(storageState()).`as`("Repeating the deletion does not touch the storage").isEqualTo(storage)
    }

    private fun storageKey(publicUrl: String): String = ContractFakeS3.keyOf(publicUrl) ?: error("Not a storage URL")

    private fun seedPrivateNote(postId: Long) {
        execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?), (?)", OWNER, OTHER)
        execute(
            "INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,context_type,context_id," +
                "created_at,expires_at) VALUES (?::uuid,?,?,'F4 note','POST',?,now(),now() + interval '1 day')",
            NOTE, OWNER, OTHER, postId,
        )
    }

    private companion object {
        const val OWNER = 9_904_042L
        const val OTHER = 9_904_043L
        const val COMMENT = 9_904_100L
        const val NOTE = "f4000000-0000-0000-0000-000000000001"
    }
}
