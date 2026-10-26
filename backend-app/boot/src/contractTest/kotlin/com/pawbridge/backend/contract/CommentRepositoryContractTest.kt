package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentDeletion
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** The comment repositories called directly: insert, content update, soft delete and the two reads. */
class CommentRepositoryContractTest : CommentContractTestBase() {
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

    private val clock: Clock = Clock.fixed(Instant.parse("2026-03-05T01:02:03.456789Z"), ZoneOffset.UTC)
    private lateinit var writer: JdbcCommentWriter
    private lateinit var reader: JdbcCommentReader

    @BeforeEach
    fun build() {
        seedUser(AUTHOR, "댓글 작성자")
        seedUser(OTHER, "댓글 타인")
        seedPost(POST, AUTHOR)
        seedPost(POST + 1, AUTHOR)
        writer = JdbcCommentWriter(write, writeTx, clock)
        reader = JdbcCommentReader(read, readTx)
    }

    @Test
    fun `게시글과 내용일 때__작성하면__행이 생기고 전체 정밀도의 시각과 식별자를 돌려준다`() {
        // given
        // when
        val saved = writer.create(POST, AUTHOR, "첫 댓글")
        val stored = commentRow(saved.commentId)

        // then
        assertThat(saved.createdAt).isEqualTo(LocalDateTime.of(2026, 3, 5, 1, 2, 3, 456_789_000))
        assertThat(saved.updatedAt).isEqualTo(saved.createdAt)
        assertThat(stored.get("post_id").asLong()).isEqualTo(POST)
        assertThat(stored.get("author_id").asLong()).isEqualTo(AUTHOR)
        assertThat(stored.get("content").asText()).isEqualTo("첫 댓글")
        assertThat(stored.get("created_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(stored.get("deleted_at").isNull).isTrue()
    }

    @Test
    fun `네 바이트 문자가 들어 있을 때__작성하거나 수정하면__문자 변환 예외이고 행은 그대로다`() {
        // given
        val id = writer.create(POST, AUTHOR, "원래 내용").commentId

        // when
        // then
        assertNoSideEffects {
            assertThatThrownBy { writer.create(POST, AUTHOR, "이모지 😀") }.isInstanceOf(UnsupportedCommentCharactersException::class.java)
            assertThatThrownBy { writer.update(id, "이모지 😀") }.isInstanceOf(UnsupportedCommentCharactersException::class.java)
        }
    }

    @Test
    fun `활성 댓글일 때__내용을 수정하면__내용과 수정 시각만 바뀌고 다른 댓글은 그대로다`() {
        // given
        val id = writer.create(POST, AUTHOR, "수정 전").commentId
        val other = writer.create(POST, OTHER, "다른 댓글").commentId
        execute("UPDATE pawbridge_community.comments SET updated_at = '2025-01-01 00:00:00'")
        val otherBefore = commentRow(other)

        // when
        writer.update(id, "수정 후")
        val stored = commentRow(id)

        // then
        assertThat(stored.get("content").asText()).isEqualTo("수정 후")
        assertThat(stored.get("updated_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(stored.get("created_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(commentRow(other)).isEqualTo(otherBefore)
        assertThatThrownBy { writer.update(COMMENT_BASE + 99, "없음") }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `작성자의 활성 댓글일 때__삭제하면__논리 삭제하고 남의 댓글과 없는 댓글과 이미 지운 댓글은 거부한다`() {
        // given
        val id = writer.create(POST, AUTHOR, "삭제 대상").commentId
        val deletion = JdbcCommentDeletion(write, writeTx, clock)

        // when
        assertThatThrownBy { deletion.delete(id, OTHER) }.isInstanceOf(CommentNotOwnedException::class.java)
        val untouched = commentRow(id).get("deleted_at").isNull
        deletion.delete(id, AUTHOR)
        val stored = commentRow(id)

        // then
        assertThat(untouched).isTrue()
        assertThat(stored.get("deleted_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThat(stored.get("content").asText()).isEqualTo("삭제 대상")
        assertThatThrownBy { deletion.delete(id, AUTHOR) }.isInstanceOf(CommentNotFoundException::class.java)
        assertThatThrownBy { deletion.delete(COMMENT_BASE + 99, AUTHOR) }.isInstanceOf(CommentNotFoundException::class.java)
    }

    @Test
    fun `삭제된 댓글이 섞여 있을 때__단건과 게시글별로 읽으면__활성 댓글만 최신순으로 준다`() {
        // given
        seedComment(COMMENT_BASE + 1, POST, AUTHOR, "오래된", createdAt = "2025-01-01 00:00:01")
        seedComment(COMMENT_BASE + 2, POST, OTHER, "최신", createdAt = "2025-01-01 00:00:03")
        seedComment(COMMENT_BASE + 3, POST, AUTHOR, "삭제", createdAt = "2025-01-01 00:00:05", deletedAt = "2025-02-01 00:00:00")
        seedComment(COMMENT_BASE + 4, POST + 1, AUTHOR, "다른 글", createdAt = "2025-01-01 00:00:02")

        // when
        val list = reader.findByPostId(POST)
        val one = reader.findById(COMMENT_BASE + 1)

        // then
        assertThat(list.map { it.commentId }).isEqualTo(listOf(COMMENT_BASE + 2, COMMENT_BASE + 1))
        assertThat(list.map { it.content }).isEqualTo(listOf("최신", "오래된"))
        assertThat(one.get().authorId).isEqualTo(AUTHOR)
        assertThat(one.get().createdAt).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0, 1))
        assertThat(reader.findById(COMMENT_BASE + 3)).isEmpty
        assertThat(reader.findByPostId(POST + 9)).isEmpty()
    }

    private companion object {
        const val AUTHOR = 9_922_001L
        const val OTHER = 9_922_002L
        const val POST = 9_922_100L
        const val COMMENT_BASE = 9_922_200L
    }
}
