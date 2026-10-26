package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Updating a post as its owner with SDK media replacement (replaces the packaged F5 smoke). */
class PostUpdateContractTest : PostContractTestBase() {
    @BeforeEach
    fun seedUsers() {
        seedUser(OWNER, "수정 작성자")
        seedUser(OTHER, "수정 타인")
    }

    @Test
    fun `토큰이 없거나 리프레시 토큰일 때__글을 수정하면__401이고 DB와 저장소는 그대로다`() {
        // given
        val postId = createPost(accessToken(OWNER), files = listOf(Upload("old.jpg", "image/jpeg", "F5-old".toByteArray()))).get("postId").asLong()

        // when
        // then
        assertNoSideEffects {
            assertThat(put(postId, mapOf("title" to "무토큰"), token = null).status).isEqualTo(401)
            assertThat(put(postId, mapOf("title" to "리프레시"), token = rejectedTokens(OWNER).getValue("refresh")).status).isEqualTo(401)
        }
    }

    @Test
    fun `다른 작성자의 글일 때__관리자가 수정하면__403이고 DB와 저장소는 그대로다`() {
        // given
        val foreign = createPost(accessToken(OTHER), files = listOf(Upload("old.jpg", "image/jpeg", "F5-foreign".toByteArray())))

        // when
        val reply = assertNoSideEffects {
            put(foreign.get("postId").asLong(), mapOf("title" to "관리자 수정"), token = accessToken(OWNER, "ROLE_ADMIN"))
        }

        // then
        assertThat(reply.status).isEqualTo(403)
        assertThat(reply.code).isEqualTo(403)
    }

    @Test
    fun `없는 글일 때__수정하면__404이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        val reply = assertNoSideEffects { put(99_999_999L, emptyMap(), token = accessToken(OWNER)) }

        // then
        assertThat(reply.status).isEqualTo(404)
    }

    @Test
    fun `제목과 빈 내용과 새 영상일 때__작성자가 수정하면__이전 값을 응답하고 저장소는 삭제 뒤 업로드 순서다`() {
        // given
        val token = accessToken(OWNER)
        val first = createPost(token, files = listOf(Upload("old.jpg", "image/jpeg", "F5-old".toByteArray())))
        val postId = first.get("postId").asLong()
        val before = readPost(postId).data
        val requestsBefore = ContractFakeS3.requests().size

        // when
        val reply = put(
            postId, mapOf("title" to "수정된 제목", "content" to ""), listOf(Upload("new.mp4", "video/mp4", "F5-new-video".toByteArray())),
            token,
        )

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.message).isEqualTo("게시글이 수정되었습니다.")
        assertThat(reply.data.size()).isEqualTo(9)
        assertThat(reply.data.get("authorId").asLong()).isEqualTo(OWNER)
        assertThat(reply.data.get("title").asText()).isEqualTo("수정된 제목")
        assertThat(reply.data.get("content").asText()).isEmpty()
        assertThat(reply.data.get("updatedAt")).`as`("The response shows the time before the flush").isEqualTo(before.get("updatedAt"))
        val current = readPost(postId).data
        for (field in listOf("postId", "authorId", "authorNickname", "title", "content", "boardType", "imageUrls", "createdAt")) {
            assertThat(current.get(field)).`as`(field).isEqualTo(reply.data.get(field))
        }
        val changed = ContractFakeS3.requests().drop(requestsBefore)
        assertThat(changed.map { it.method }).isEqualTo(listOf("DELETE", "PUT"))
        assertThat(changed[0].key).isEqualTo(storageKeyOf(first.get("imageUrls").get(0)))
        val newKey = storageKeyOf(reply.data.get("imageUrls").get(0))
        val stored = ContractFakeS3.objects().getValue(newKey)
        assertThat(stored.sha256).isEqualTo(ContractFakeS3.sha256("F5-new-video".toByteArray()))
        assertThat(stored.contentType).isEqualTo("video/mp4")
        assertThat(ContractFakeS3.objects()).`as`("The replaced object is gone").hasSize(1)
        assertThat(outboxCount(postId)).`as`("The monolith writes no POST_UPDATED outbox row").isZero()
    }

    @Test
    fun `바뀐 값이 없을 때__수정하면__행과 아웃박스가 그대로다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token).get("postId").asLong()
        val unchanged = postBusinessState(postId)

        // when
        val reply = put(postId, emptyMap(), token = token)

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(postBusinessState(postId)).isEqualTo(unchanged)
    }

    @Test
    fun `빈 파일 하나일 때__수정하면__이미지 목록이 비고 기존 객체만 삭제하며 업로드는 없다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token, files = listOf(Upload("old.jpg", "image/jpeg", "F5-old".toByteArray()))).get("postId").asLong()
        val putsBefore = ContractFakeS3.requestCount("PUT")
        val deletesBefore = ContractFakeS3.requestCount("DELETE")

        // when
        val reply = put(postId, emptyMap(), listOf(Upload("empty.jpg", "image/jpeg", ByteArray(0))), token)

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.data.get("imageUrls").size()).isZero()
        assertThat(ContractFakeS3.requestCount("PUT")).isEqualTo(putsBefore)
        assertThat(ContractFakeS3.requestCount("DELETE")).isEqualTo(deletesBefore + 1)
    }

    @Test
    fun `이전 객체 삭제가 거부될 때__수정하면__삭제는 최선 노력이라 새 업로드를 막지 않는다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token, files = listOf(Upload("old.jpg", "image/jpeg", "best-effort-old".toByteArray()))).get("postId").asLong()
        val putsBefore = ContractFakeS3.requestCount("PUT")
        ContractFakeS3.failNextDelete(after = 0, count = 1)

        // when
        val reply = put(postId, emptyMap(), listOf(Upload("new.jpg", "image/jpeg", "best-effort-new".toByteArray())), token)

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(ContractFakeS3.requestCount("PUT")).isEqualTo(putsBefore + 1)
    }

    @Test
    fun `새 업로드가 거부될 때__수정하면__500이고 DB는 그대로이며 지운 이전 객체는 되살리지 않는다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token, files = listOf(Upload("old.jpg", "image/jpeg", "upload-failure-old".toByteArray()))).get("postId").asLong()
        val unchanged = postBusinessState(postId)
        val deletesBefore = ContractFakeS3.requestCount("DELETE")
        ContractFakeS3.failNextPut(after = 0, count = 1)

        // when
        val failed = put(postId, mapOf("title" to "실패 변경"), listOf(Upload("new.jpg", "image/jpeg", "upload-failure-new".toByteArray())), token)

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(postBusinessState(postId)).isEqualTo(unchanged)
        assertThat(ContractFakeS3.requestCount("DELETE")).isEqualTo(deletesBefore + 1)
        val recovered = put(postId, mapOf("title" to "복구 변경"), listOf(Upload("new.jpg", "image/jpeg", "upload-recovered".toByteArray())), token)
        assertThat(recovered.status).isEqualTo(200)
        assertThat(recovered.data.get("title").asText()).isEqualTo("복구 변경")
    }

    @Test
    fun `검색 투영 쓰기 권한이 없을 때__수정하면__500이고 행은 롤백되며 외부 보상은 없고 복구 후 수정된다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token, files = listOf(Upload("old.jpg", "image/jpeg", "rollback-old".toByteArray()))).get("postId").asLong()
        val unchanged = postBusinessState(postId)
        val putsBefore = ContractFakeS3.requestCount("PUT")
        val deletesBefore = ContractFakeS3.requestCount("DELETE")

        // when
        val failed = ContractPostgres.withRevoked("INSERT", "pawbridge_community.post_search_documents") {
            put(postId, mapOf("title" to "rollback"), listOf(Upload("new.jpg", "image/jpeg", "rollback-new".toByteArray())), token)
        }

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(postBusinessState(postId)).isEqualTo(unchanged)
        assertThat(ContractFakeS3.requestCount("DELETE")).isEqualTo(deletesBefore + 1)
        assertThat(ContractFakeS3.requestCount("PUT")).isEqualTo(putsBefore + 1)
        val recovered = put(postId, mapOf("title" to "복구"), listOf(Upload("new.jpg", "image/jpeg", "rollback-recovered".toByteArray())), token)
        assertThat(recovered.status).isEqualTo(200)
    }

    @Test
    fun `삭제된 글일 때__수정하면__공개 조회와 같이 404다`() {
        // given
        val token = accessToken(OWNER)
        val postId = createPost(token).get("postId").asLong()
        assertThat(deletePost(postId, token).status).isEqualTo(200)

        // when
        val reply = assertNoSideEffects { put(postId, emptyMap(), token = token) }

        // then
        assertThat(readPost(postId).status).isEqualTo(404)
        assertThat(reply.status).isEqualTo(404)
    }

    private fun put(postId: Long, fields: Map<String, String>, files: List<Upload> = emptyList(), token: String?): Reply =
        multipart("PUT", "/api/v1/posts/$postId", fields, files, token, mapOf("X-User-Id" to "999999", "X-User-Role" to "ROLE_ADMIN"))

    private companion object {
        const val OWNER = 9_905_042L
        const val OTHER = 9_905_043L
    }
}
