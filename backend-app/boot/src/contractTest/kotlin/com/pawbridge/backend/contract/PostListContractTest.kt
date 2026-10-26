package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The public post list (replaces the packaged F6 smoke): every GET reads only and leaves storage untouched. */
class PostListContractTest : PostContractTestBase() {
    @BeforeEach
    fun seedAuthor() {
        seedUser(AUTHOR, "목록 합성작성자")
    }

    @Test
    fun `활성 글이 없을 때__목록을 조회하면__삭제된 글만 있어도 빈 배열이다`() {
        // given
        seedPost(POST, AUTHOR, deletedAt = "2025-01-03 01:02:03")

        // when
        val rows = list()

        // then
        assertThat(rows).isEmpty()
    }

    @Test
    fun `글이 두 개일 때__목록을 조회하면__모두 최신순으로 나오고 단건 조회와 같다`() {
        // given
        val token = accessToken(AUTHOR)
        val first = createPost(token, "F6 첫 글", files = listOf(Upload("list.jpg", "image/jpeg", "F6-list-media".toByteArray())))
        val second = createPost(token, "F6 두번째 글")

        // when
        val rows = list()

        // then
        assertThat(rows.map { it.get("postId").asLong() }.toSet())
            .isEqualTo(setOf(first.get("postId").asLong(), second.get("postId").asLong()))
        for (item in rows) {
            assertThat(item).isEqualTo(readPost(item.get("postId").asLong()).data)
        }
    }

    @Test
    fun `관계 없는 쿼리와 위조된 신원일 때__목록을 조회하면__필터링되지 않는다`() {
        // given
        val token = accessToken(AUTHOR)
        createPost(token, "F6 첫 글")
        createPost(token, "F6 두번째 글")
        val plain = list()

        // when
        val filtered = list(
            "/api/v1/posts/read?page=999&size=1&limit=0&boardType=REPORT&boardId=999&authorId=999",
            mapOf("X-User-Id" to "999", "X-User-Role" to "ROLE_ADMIN"),
        )

        // then
        assertThat(filtered).hasSize(2)
        assertThat(filtered.toSet()).isEqualTo(plain.toSet())
    }

    @Test
    fun `글을 수정하거나 삭제했을 때__목록을 조회하면__수정은 보이고 삭제는 사라진다`() {
        // given
        val token = accessToken(AUTHOR)
        val first = createPost(token, "F6 첫 글").get("postId").asLong()
        val second = createPost(token, "F6 두번째 글").get("postId").asLong()

        // when
        val updated = multipart("PUT", "/api/v1/posts/$first", mapOf("title" to "F6 수정 노출", "content" to "수정 본문"), token = token)
        val afterUpdate = list()
        val detailAfterUpdate = readPost(first).data
        assertThat(deletePost(first, token).status).isEqualTo(200)
        val afterDelete = list()
        assertThat(deletePost(second, token).status).isEqualTo(200)
        val afterAll = list()

        // then
        assertThat(updated.status).isEqualTo(200)
        assertThat(detailAfterUpdate.get("title").asText()).isEqualTo("F6 수정 노출")
        assertThat(afterUpdate.single { it.get("postId").asLong() == first }).isEqualTo(detailAfterUpdate)
        assertThat(afterDelete.map { it.get("postId").asLong() }).isEqualTo(listOf(second))
        assertThat(readPost(first).status).isEqualTo(404)
        assertThat(afterAll).isEmpty()
    }

    @Test
    fun `작성자가 없거나 닉네임을 읽을 수 없을 때__목록을 조회하면__글마다 대체 이름이고 복구된다`() {
        // given
        val token = accessToken(AUTHOR)
        val first = createPost(token, "F6 첫 글").get("postId").asLong()
        val second = createPost(token, "F6 두번째 글").get("postId").asLong()
        execute("UPDATE pawbridge_community.posts SET author_id = ? WHERE post_id = ?", MISSING_AUTHOR, second)

        // when
        val missingAuthor = list().associateBy { it.get("postId").asLong() }
        val unreadable = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") { list() }
        val recovered = list().associateBy { it.get("postId").asLong() }

        // then
        assertThat(missingAuthor.getValue(second).get("authorNickname").asText()).isEqualTo("사용자$MISSING_AUTHOR")
        assertThat(missingAuthor.getValue(first).get("authorNickname").asText()).isEqualTo("목록 합성작성자")
        for (item in unreadable) {
            assertThat(item.get("authorNickname").asText()).isEqualTo("사용자" + item.get("authorId").asLong())
        }
        assertThat(recovered.getValue(first).get("authorNickname").asText()).isEqualTo("목록 합성작성자")
    }

    @Test
    fun `글 조회 권한이 없을 때__목록을 조회하면__안전한 500이고 복구되면 다시 조회된다`() {
        // given
        createPost(accessToken(AUTHOR), "F6 첫 글")
        createPost(accessToken(AUTHOR), "F6 두번째 글")

        // when
        val failed = ContractPostgres.withRevoked(
            "SELECT(post_id, author_id, title, content, board_type, image_urls, deleted_at, created_at, updated_at)",
            "pawbridge_community.posts",
        ) { assertNoSideEffects { get("/api/v1/posts/read") } }

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(failed.code).isEqualTo(500)
        assertThat(failed.message).isEqualTo("서버 에러!")
        assertThat(failed.data.isNull).isTrue()
        assertThat(list()).hasSize(2)
    }

    @Test
    fun `이미지 JSON이 문자열 목록이 아닐 때__목록을 조회하면__안전한 500이고 되돌리면 다시 조회된다`() {
        // given
        val first = createPost(accessToken(AUTHOR), "F6 첫 글").get("postId").asLong()
        val current = readPost(first).data
        val saved = scalar("SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id = ?", first)

        // when
        execute("UPDATE pawbridge_community.posts SET image_urls = '{\"invalid\":true}'::json WHERE post_id = ?", first)
        val failed = assertNoSideEffects { get("/api/v1/posts/read") }
        execute("UPDATE pawbridge_community.posts SET image_urls = ?::json WHERE post_id = ?", saved, first)

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(failed.text).isEqualTo("""{"code":500,"message":"서버 에러!","data":null}""")
        assertThat(list().single { it.get("postId").asLong() == first }).isEqualTo(current)
    }

    /** One list GET: reads only, never touches the storage, and has the documented envelope and ordering. */
    private fun list(path: String = "/api/v1/posts/read", headers: Map<String, String> = emptyMap()): List<JsonNode> {
        val reply = assertNoSideEffects { get(path, headers = headers) }
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
        assertThat(reply.data.isArray).isTrue()
        val rows = reply.data.toList()
        for (item in rows) {
            assertThat(item.fieldNames().asSequence().toSet()).isEqualTo(FIELDS)
        }
        val times = rows.map { java.time.LocalDateTime.parse(it.get("createdAt").asText()) }
        assertThat(times).`as`("Ordered by createdAt descending").isEqualTo(times.sortedDescending())
        return rows
    }

    private companion object {
        const val AUTHOR = 9_906_042L
        const val MISSING_AUTHOR = 9_906_099L
        const val POST = 9_906_001L
        val FIELDS = setOf("postId", "authorId", "authorNickname", "title", "content", "boardType", "imageUrls", "createdAt", "updatedAt")
    }
}
