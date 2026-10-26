package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import java.net.URLEncoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The public post search (replaces the packaged F7 smoke): reads only, fails closed on stale projections. */
class PostSearchContractTest : PostContractTestBase() {
    @BeforeEach
    fun seedAuthor() {
        seedUser(AUTHOR, NICKNAME)
    }

    @Test
    fun `활성 글이 없을 때__검색하면__빈 배열이다`() {
        // given
        // when
        val rows = search("강아지")

        // then
        assertThat(rows).isEmpty()
    }

    @Test
    fun `글을 작성했을 때__검색하면__바로 검색되고 단건 조회와 날짜 외에는 같다`() {
        // given
        val token = accessToken(AUTHOR)
        val dog = createPost(token, "강아지", files = listOf(Upload("search.jpg", "image/jpeg", "F7-search-media".toByteArray())))
        createPost(token, "고양이")

        // when
        val rows = search("강아지")

        // then
        assertThat(rows.map { it.get("postId").asLong() }).isEqualTo(listOf(dog.get("postId").asLong()))
        val detail = readPost(dog.get("postId").asLong()).data
        for (field in FIELDS - setOf("createdAt", "updatedAt")) {
            assertThat(rows[0].get(field)).`as`(field).isEqualTo(detail.get(field))
        }
    }

    @Test
    @Tag("timezone-sensitive")
    fun `글이 저장돼 있을 때__검색하면__날짜는 저장된 시각 그대로 직접 읽은 값이다`() {
        // given
        val dog = createPost(accessToken(AUTHOR), "강아지").get("postId").asLong()
        execute(
            "UPDATE pawbridge_community.posts SET created_at = '2026-10-17 15:01:02.123456', updated_at = '2026-10-17 16:02:00' WHERE post_id = ?",
            dog,
        )

        // when
        val row = search("강아지").single()

        // then
        assertThat(row.get("createdAt").asText()).isEqualTo("2026-10-17T15:01:02.123456")
        assertThat(row.get("updatedAt").asText()).isEqualTo("2026-10-17T16:02:00")
    }

    @Test
    fun `두 낱말일 때__검색하면__분석된 OR 검색으로 둘 다 나오고 쿼리 파라미터와 신원은 무시된다`() {
        // given
        val token = accessToken(AUTHOR)
        val dog = createPost(token, "강아지").get("postId").asLong()
        val cat = createPost(token, "고양이").get("postId").asLong()

        // when
        val both = search("강아지 고양이")
        val ignored = search(
            "강아지 고양이", "&page=999&size=1&boardType=REPORT",
            mapOf("X-User-Id" to "999", "X-User-Role" to "ROLE_ADMIN", "Authorization" to "Bearer invalid"),
        )

        // then
        assertThat(both.map { it.get("postId").asLong() }.toSet()).isEqualTo(setOf(dog, cat))
        assertThat(ignored).isEqualTo(both)
    }

    @Test
    fun `다른 글의 검색 문서가 낡았을 때__검색하면__503이고 읽기가 고치지 않으며 복구되면 다시 검색된다`() {
        // given
        val token = accessToken(AUTHOR)
        createPost(token, "강아지")
        val cat = createPost(token, "고양이").get("postId").asLong()
        execute("UPDATE pawbridge_community.post_search_documents SET title_hash = 'stale' WHERE post_id = ?", cat)

        // when
        val stale = searchReply("강아지")
        val staleAgain = searchReply("강아지")
        execute(
            "UPDATE pawbridge_community.post_search_documents d SET title_hash = md5(p.title) FROM pawbridge_community.posts p " +
                "WHERE d.post_id = p.post_id AND p.post_id = ?",
            cat,
        )
        val repaired = search("강아지")

        // then
        for (reply in listOf(stale, staleAgain)) {
            assertThat(reply.status).isEqualTo(503)
            assertThat(reply.text).isEqualTo("""{"code":503,"message":"검색 서비스를 사용할 수 없습니다.","data":null}""")
        }
        assertThat(repaired).hasSize(1)
    }

    @Test
    fun `토큰 열 권한이 없을 때__검색하면__503이고 복구되면 다시 검색된다`() {
        // given
        createPost(accessToken(AUTHOR), "강아지")

        // when
        val denied = ContractPostgres.withRevoked("SELECT(tokens)", "pawbridge_community.post_search_documents") { searchReply("강아지") }
        val recovered = search("강아지")

        // then
        assertThat(denied.status).isEqualTo(503)
        assertThat(denied.message).isEqualTo("검색 서비스를 사용할 수 없습니다.")
        assertThat(recovered).hasSize(1)
    }

    @Test
    fun `닉네임을 읽을 수 없을 때__검색하면__글마다 대체 이름이고 복구되면 실제 닉네임이다`() {
        // given
        val token = accessToken(AUTHOR)
        createPost(token, "강아지")
        createPost(token, "고양이")

        // when
        val fallback = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") { search("강아지 고양이") }
        val recovered = search("강아지 고양이")

        // then
        assertThat(fallback).hasSize(2)
        assertThat(fallback.map { it.get("authorNickname").asText() }.toSet()).isEqualTo(setOf("사용자$AUTHOR"))
        assertThat(recovered.map { it.get("authorNickname").asText() }.toSet()).isEqualTo(setOf(NICKNAME))
    }

    @Test
    fun `이미지 JSON이 잘못됐을 때__검색하면__안전한 500이다`() {
        // given
        val dog = createPost(accessToken(AUTHOR), "강아지").get("postId").asLong()
        val saved = scalar("SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id = ?", dog)
        execute("UPDATE pawbridge_community.posts SET image_urls = '{\"invalid\":true}'::json WHERE post_id = ?", dog)

        // when
        val failed = searchReply("강아지")
        execute("UPDATE pawbridge_community.posts SET image_urls = ?::json WHERE post_id = ?", saved, dog)

        // then
        assertThat(failed.status).isEqualTo(500)
        assertThat(failed.text).isEqualTo("""{"code":500,"message":"서버 에러!","data":null}""")
        assertThat(search("강아지")).hasSize(1)
    }

    @Test
    fun `글을 수정하거나 삭제했을 때__검색하면__옛 낱말은 사라지고 새 낱말이 나오며 삭제는 검색되지 않는다`() {
        // given
        val token = accessToken(AUTHOR)
        val dog = createPost(token, "강아지").get("postId").asLong()
        val cat = createPost(token, "고양이").get("postId").asLong()

        // when
        val updated = multipart("PUT", "/api/v1/posts/$dog", mapOf("title" to "고양이"), token = token)
        val oldTerm = search("강아지")
        val newTerm = search("고양이")
        assertThat(deletePost(dog, token).status).isEqualTo(200)
        val afterDelete = search("고양이")
        assertThat(deletePost(cat, token).status).isEqualTo(200)
        val afterAll = search("고양이")

        // then
        assertThat(updated.status).isEqualTo(200)
        assertThat(oldTerm).isEmpty()
        assertThat(newTerm.map { it.get("postId").asLong() }.toSet()).isEqualTo(setOf(dog, cat))
        assertThat(afterDelete.map { it.get("postId").asLong() }).isEqualTo(listOf(cat))
        assertThat(readPost(dog).status).isEqualTo(404)
        assertThat(afterAll).isEmpty()
    }

    private fun searchReply(keyword: String, extra: String = "", headers: Map<String, String> = emptyMap()): Reply {
        val path = "/api/v1/posts/search?keyword=" + URLEncoder.encode(keyword, Charsets.UTF_8) + extra
        return assertNoSideEffects { get(path, headers = headers) }
    }

    /** One successful search: a whole array of at most ten complete posts, with zero writes and zero storage calls. */
    private fun search(keyword: String, extra: String = "", headers: Map<String, String> = emptyMap()): List<JsonNode> {
        val reply = searchReply(keyword, extra, headers)
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
        assertThat(reply.data.isArray).isTrue()
        assertThat(reply.data.size()).isLessThanOrEqualTo(10)
        for (item in reply.data) {
            assertThat(item.fieldNames().asSequence().toSet()).isEqualTo(FIELDS)
        }
        return reply.data.toList()
    }

    private companion object {
        const val AUTHOR = 9_907_042L
        const val NICKNAME = "검색 합성작성자"
        val FIELDS = setOf("postId", "authorId", "authorNickname", "title", "content", "boardType", "imageUrls", "createdAt", "updatedAt")
    }
}
