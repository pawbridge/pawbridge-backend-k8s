package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcAdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostDeletion
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchReader
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

/** The post repositories called directly: write, update, soft delete, read, page, search and the admin statistics. */
class PostRepositoryContractTest : PostContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var writeTx: TransactionTemplate

    @Autowired
    @Qualifier("readJdbc")
    private lateinit var read: JdbcTemplate

    @Autowired
    @Qualifier("readOnlyTransaction")
    private lateinit var readTx: TransactionTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    private val at: Instant = Instant.parse("2026-03-05T01:02:03.456789Z")
    private lateinit var writer: JdbcPostWriter
    private lateinit var reader: JdbcPostReader

    @BeforeEach
    fun build() {
        seedUser(AUTHOR, "저장소 작성자")
        seedUser(OTHER, "저장소 타인")
        writer = JdbcPostWriter(write, writeTx, mapper, Clock.fixed(at, ZoneOffset.UTC))
        reader = JdbcPostReader(read, readTx, mapper)
    }

    @AfterEach
    fun close() {
        writer.close()
    }

    // ---- write -------------------------------------------------------------------------------------------

    @Test
    fun `게시글과 이미지일 때__저장하면__게시글 행과 검색 문서가 한 번에 생기고 저장 시각을 돌려준다`() {
        // given
        val post = PostToCreate(AUTHOR, "저장 제목", "저장 본문", PostBoardType.REPORT, listOf("https://fixture.invalid/a.jpg", null))

        // when
        val saved = writer.create(post)
        val stored = row("pawbridge_community.posts", "post_id", saved.postId())

        // then
        assertThat(saved.createdAt()).isEqualTo(LocalDateTime.of(2026, 3, 5, 1, 2, 3, 456_789_000))
        assertThat(stored.get("author_id").asLong()).isEqualTo(AUTHOR)
        assertThat(stored.get("board_type").asText()).isEqualTo("REPORT")
        assertThat(stored.get("image_urls").toString()).isEqualTo("[\"https://fixture.invalid/a.jpg\",null]")
        assertThat(stored.get("created_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(stored.get("deleted_at").isNull).isTrue()
        assertThat(searchDocumentCount(saved.postId())).isOne()
    }

    @Test
    fun `네 바이트 문자가 들어 있을 때__저장하면__문자 변환 예외이고 게시글도 검색 문서도 남지 않는다`() {
        // given
        val post = PostToCreate(AUTHOR, "이모지 😀", "본문", PostBoardType.COMMUNICATION, emptyList())

        // when
        // then
        assertNoSideEffects {
            assertThatThrownBy { writer.create(post) }.isInstanceOf(UnsupportedPostCharactersException::class.java)
        }
    }

    @Test
    fun `일부 필드만 바꿀 때__수정하면__나머지는 유지하고 검색 문서를 새로 쓰며 작성자가 아니거나 삭제된 글은 거부한다`() {
        // given
        val id = writer.create(PostToCreate(AUTHOR, "수정 전 제목", "수정 전 본문", PostBoardType.COMMUNICATION, listOf("u1"))).postId()
        val oldHash = scalar("SELECT title_hash FROM pawbridge_community.post_search_documents WHERE post_id = ?", id)
        seedPost(POST_BASE + 1, AUTHOR, deletedAt = "2026-01-01 00:00:00")
        val before = postBusinessState(id)

        // when
        assertThatThrownBy { writer.update(id, OTHER, "탈취", null, null) }.isInstanceOf(PostNotOwnedException::class.java)
        val afterRejected = postBusinessState(id)
        val updated = writer.update(id, AUTHOR, "수정 후 제목", null, null)
        val stored = postRow(id)

        // then
        assertThat(afterRejected).isEqualTo(before)
        assertThat(updated.title()).isEqualTo("수정 후 제목")
        assertThat(updated.content()).isEqualTo("수정 전 본문")
        assertThat(stored.get("title").asText()).isEqualTo("수정 후 제목")
        assertThat(stored.get("content").asText()).isEqualTo("수정 전 본문")
        assertThat(stored.get("image_urls").toString()).isEqualTo("[\"u1\"]")
        assertThat(stored.get("updated_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(scalar("SELECT title_hash FROM pawbridge_community.post_search_documents WHERE post_id = ?", id)).isNotEqualTo(oldHash)
        assertThatThrownBy { writer.update(POST_BASE + 1, AUTHOR, "삭제됨", null, null) }.isInstanceOf(PostNotFoundException::class.java)
        assertThatThrownBy { writer.update(POST_BASE + 99, AUTHOR, "없음", null, null) }.isInstanceOf(PostNotFoundException::class.java)
    }

    @Test
    fun `작성자의 활성 글일 때__삭제하면__논리 삭제하고 검색 문서를 지우며 남의 글과 이미 지운 글은 거부한다`() {
        // given
        val id = writer.create(PostToCreate(AUTHOR, "삭제 제목", "삭제 본문", PostBoardType.COMMUNICATION, emptyList())).postId()
        val deletion = JdbcPostDeletion(write, writeTx, Clock.fixed(at, ZoneOffset.UTC))

        // when
        assertThatThrownBy { deletion.delete(id, OTHER) }.isInstanceOf(PostNotOwnedException::class.java)
        val untouched = postRow(id).get("deleted_at").isNull to searchDocumentCount(id)
        deletion.delete(id, AUTHOR)
        val stored = postRow(id)

        // then
        assertThat(untouched).isEqualTo(true to 1L)
        assertThat(stored.get("deleted_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(stored.get("updated_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(searchDocumentCount(id)).isZero()
        assertThatThrownBy { deletion.delete(id, AUTHOR) }.isInstanceOf(PostNotFoundException::class.java)
        assertThatThrownBy { deletion.delete(POST_BASE + 99, AUTHOR) }.isInstanceOf(PostNotFoundException::class.java)
    }

    // ---- read --------------------------------------------------------------------------------------------

    @Test
    fun `활성 글과 삭제된 글이 있을 때__단건과 전체를 읽으면__활성 글만 최신순이고 이미지 null과 순서를 지킨다`() {
        // given
        seedPost(POST_BASE + 1, AUTHOR, title = "오래된", createdAt = "2025-01-01 00:00:01", imageUrls = "[\"b\",null,\"a\",\"b\"]")
        seedPost(POST_BASE + 2, AUTHOR, title = "최신", createdAt = "2025-01-01 00:00:03")
        seedPost(POST_BASE + 3, AUTHOR, title = "삭제", createdAt = "2025-01-01 00:00:05", deletedAt = "2025-02-01 00:00:00")

        // when
        val all = reader.findAll()
        val one = reader.findById(POST_BASE + 1)

        // then
        assertThat(all.map { it.postId() }).isEqualTo(listOf(POST_BASE + 2, POST_BASE + 1))
        assertThat(one.get().imageUrls()).isEqualTo(listOf("b", null, "a", "b"))
        assertThat(one.get().createdAt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0, 1))
        assertThat(reader.findById(POST_BASE + 3)).isEmpty
        assertThat(reader.findById(POST_BASE + 99)).isEmpty
    }

    @Test
    fun `삭제된 글이 섞인 다섯 개의 글일 때__페이지로 읽으면__정렬과 크기대로 자르고 총계는 활성 글 수다`() {
        // given
        for ((offset, title) in listOf("다", "가", "라", "나").withIndex()) {
            seedPost(POST_BASE + offset, AUTHOR, title = "$title 글", createdAt = "2025-01-01 00:00:0${offset + 1}")
        }
        seedPost(POST_BASE + 9, AUTHOR, title = "삭제", deletedAt = "2025-02-01 00:00:00")
        fun page(number: Int, size: Int, vararg orders: PostPageReader.Order) = reader.findPage(PostPageReader.Request(number, size, orders.toList()))

        // when
        val byTitle = page(0, 3, PostPageReader.Order("title", true, false))
        val second = page(1, 3, PostPageReader.Order("title", true, false))
        val newestFirst = page(0, 2, PostPageReader.Order("createdAt", false, false))
        val beyond = page(5, 3, PostPageReader.Order("title", true, false))

        // then
        assertThat(byTitle.content().map { it.title() }).isEqualTo(listOf("가 글", "나 글", "다 글"))
        assertThat(byTitle.total()).isEqualTo(4)
        assertThat(second.content().map { it.title() }).isEqualTo(listOf("라 글"))
        assertThat(second.total()).isEqualTo(4)
        assertThat(newestFirst.content().map { it.postId() }).isEqualTo(listOf(POST_BASE + 3, POST_BASE + 2))
        assertThat(beyond.content()).isEmpty()
        assertThat(beyond.total()).isEqualTo(4)
        assertThatThrownBy { page(0, 3, PostPageReader.Order("password", true, false)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `검색 문서가 있는 글들일 때__검색하면__제목 일치가 본문 일치보다 앞서고 삭제된 글은 빠지며 빈 검색어는 비어 있다`() {
        // given
        val titleHit = writer.create(PostToCreate(AUTHOR, "강아지 입양", "다른 이야기", PostBoardType.ADOPTION, emptyList())).postId()
        val bodyHit = writer.create(PostToCreate(AUTHOR, "다른 제목", "강아지 이야기", PostBoardType.COMMUNICATION, emptyList())).postId()
        val gone = writer.create(PostToCreate(AUTHOR, "강아지 삭제", "본문", PostBoardType.COMMUNICATION, emptyList())).postId()
        writer.create(PostToCreate(AUTHOR, "고양이", "무관", PostBoardType.COMMUNICATION, emptyList()))
        JdbcPostDeletion(write, writeTx).delete(gone, AUTHOR)
        val search = JdbcPostSearchReader(read, readTx, mapper)

        for (index in 1..10) {
            writer.create(PostToCreate(AUTHOR, "다른 $index", "강아지 이야기 $index", PostBoardType.COMMUNICATION, emptyList()))
        }

        // when
        val found = search.search("강아지")

        // then
        assertThat(found).`as`("At most ten, best score first").hasSize(10)
        assertThat(found.first().postId()).isEqualTo(titleHit)
        assertThat(found.map { it.postId() }).doesNotContain(gone)
        assertThat(search.search("   ")).isEmpty()
        assertThat(search.search("없는단어")).isEmpty()
        search.close()
    }

    @Test
    fun `문서가 없는 활성 글이 있을 때__검색하면__결과 전체를 사용할 수 없음으로 처리한다`() {
        // given
        seedPost(POST_BASE + 1, AUTHOR, title = "문서 없는 강아지")
        val search = JdbcPostSearchReader(read, readTx, mapper)

        // when
        // then
        assertThatThrownBy { search.search("강아지") }.isInstanceOf(PostSearchUnavailableException::class.java)
        search.close()
    }

    // ---- statistics --------------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `어제와 오늘과 내일의 글이 있을 때__오늘 글 수를 읽으면__JVM 오늘의 활성 글만 센다`() {
        // given
        val today = LocalDate.now(ZoneId.systemDefault())
        seedPost(POST_BASE + 1, AUTHOR, createdAt = "$today 12:00:00")
        seedPost(POST_BASE + 2, AUTHOR, createdAt = "$today 12:30:00")
        seedPost(POST_BASE + 3, AUTHOR, createdAt = "$today 13:00:00", deletedAt = "$today 14:00:00")
        seedPost(POST_BASE + 4, AUTHOR, createdAt = "${today.minusDays(1)} 12:00:00")
        seedPost(POST_BASE + 5, AUTHOR, createdAt = "${today.plusDays(1)} 12:00:00")

        // when
        val count = statistics().today()

        // then
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `서울 날짜 경계의 글이 있을 때__기간 통계를 읽으면__서울 날짜별 개수와 전날 개수와 게시판별 개수를 준다`() {
        // given
        seedPost(POST_BASE + 1, AUTHOR, board = "COMMUNICATION", createdAt = "2026-03-09 15:00:00")
        seedPost(POST_BASE + 2, AUTHOR, board = "COMMUNICATION", createdAt = "2026-03-10 14:59:59")
        seedPost(POST_BASE + 3, AUTHOR, board = "REPORT", createdAt = "2026-03-10 15:00:00")
        seedPost(POST_BASE + 4, AUTHOR, board = "REPORT", createdAt = "2026-03-10 16:00:00")
        seedPost(POST_BASE + 5, AUTHOR, board = "REPORT", createdAt = "2026-03-10 16:30:00", deletedAt = "2026-03-11 00:00:00")
        seedPost(POST_BASE + 6, AUTHOR, board = "COMMUNICATION", createdAt = "2026-03-09 03:00:00")

        // when
        val period = statistics().period(LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-11"))

        // then
        assertThat(period.daily.map { it.date.toString() to it.count })
            .`as`("14:59:59 UTC is still the 10th in Seoul, 15:00:00 UTC is the 11th")
            .isEqualTo(listOf("2026-03-10" to 2L, "2026-03-11" to 2L))
        assertThat(period.previousDayCount).isEqualTo(1)
        assertThat(period.byBoardType.map { it.boardType to it.count }.toSet())
            .`as`("Board totals use the stored time range of the two days")
            .isEqualTo(setOf<Pair<String?, Long>>("COMMUNICATION" to 1L, "REPORT" to 2L))
    }

    @Test
    fun `기간이 뒤집혔거나 미래이거나 367일일 때__기간 통계를 읽으면__400 예외이고 366일은 빈 목록으로 허용한다`() {
        // given
        val query = statistics()
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        // when
        // then
        for ((start, end) in listOf(
            LocalDate.parse("2026-03-11") to LocalDate.parse("2026-03-10"),
            LocalDate.parse("2026-03-10") to today.plusDays(1),
            today.minusDays(366) to today,
        )) {
            assertThatThrownBy { query.period(start, end) }
                .isInstanceOfSatisfying(ResponseStatusException::class.java) { assertThat(it.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) }
        }
        assertThat(query.period(today.minusDays(365), today).daily).isEmpty()
    }

    private fun statistics(): AdminPostStatisticsQuery =
        JdbcAdminPostStatisticsQuery(read, readTx, readTx, Clock.systemUTC())

    private companion object {
        const val AUTHOR = 9_921_001L
        const val OTHER = 9_921_002L
        const val POST_BASE = 9_921_100L
    }
}
