package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class JdbcPostModificationTest {
    private val jdbc = mockk<JdbcOperations>()
    private val tx = FakeTransactions()
    private val calls = ArrayList<StatementCall>()
    private val connection = recordingConnection(calls) { assertThat(tx.active).isTrue() }
    private val row = FakeRow()
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T03:00:00.123456Z"), ZoneId.of("Asia/Seoul"))
    private val previous = LocalDateTime.of(2026, 1, 1, 0, 0)
    private var updated = 1

    @Test
    fun `제목만 일부 바꿀 때__수정하면__한 트랜잭션에서 잠그고 병합하고 정확한 값을 쓰고 투영한다`() {
        // given
        givenLockedRow()

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updated
        }

        // when
        val saved: PostSnapshot
        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            saved = writer.update(42, 7, "수정", null, null)
        }

        // then
        assertThat(tx.active).isFalse()
        assertThat(calls).hasSize(3)
        assertThat(calls.first().sql).contains("FOR UPDATE", "deleted_at IS NULL")
        assertThat(saved).isEqualTo(
            PostSnapshot(42, 7, "수정", "본문", "MISSING", listOf("https://fixture.invalid/old"), previous, previous),
        )
        assertThat(calls[1].values)
            .containsEntry(1, "수정")
            .containsEntry(2, "본문")
            .containsEntry(4, Timestamp.valueOf(LocalDateTime.now(clock)))
            .containsEntry(5, 42L)
            .containsEntry(6, 7L)
        assertThat(calls[2].sql).contains("post_search_documents", "ON CONFLICT", "setweight")
        assertThat(calls[2].values).containsEntry(1, 42L).containsEntry(2, "수정").containsEntry(3, "본문")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `값이 생략되었거나 동일할 때__수정하면__행 시각은 유지하되 아웃박스 없이 검색 투영은 갱신한다`(
        same: Boolean,
    ) {
        // given
        givenLockedRow()

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updated
        }

        // when
        val updatedAt = JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            writer.update(
                42,
                7,
                if (same) "제목" else null,
                if (same) "본문" else null,
                if (same) listOf("https://fixture.invalid/old") else null,
            ).updatedAt()
        }

        // then
        assertThat(updatedAt).isEqualTo(previous)
        assertThat(calls).hasSize(2)
        assertThat(calls[1].sql).contains("post_search_documents")
        assertThat(calls).noneMatch { c -> c.sql.contains("outbox_events") }
    }

    @Test
    fun `명시적 빈 필드와 빈 이미지일 때__수정하면__생성 시 게시판 제한 없이 빈 값을 쓴다`() {
        // given
        givenLockedRow()

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updated
        }

        // when
        val saved = JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            writer.update(42, 7, "", "", listOf())
        }

        // then
        assertThat(saved.title()).isEmpty()
        assertThat(saved.content()).isEmpty()
        assertThat(saved.imageUrls()).isEmpty()
        assertThat(saved.boardType()).isEqualTo("MISSING")
        assertThat(calls[1].values).containsEntry(1, "").containsEntry(2, "").containsEntry(3, "[]")
    }

    // The former NullPointerException came only from building the POST_UPDATED outbox payload with Map.of; with no outbox it no longer fails.
    @Test
    fun `이미지가 JSON null 리터럴일 때__텍스트만 수정하면__아웃박스 문장 없이 성공한다`() {
        // given
        givenLockedRow()
        row.strings["image_urls"] = "null"

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updated
        }

        // when
        val imageUrls = JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            writer.update(42, 7, "수정", null, null).imageUrls()
        }

        // then
        assertThat(imageUrls).isNull()
        assertThat(calls).noneMatch { c -> c.sql.contains("outbox_events") }
    }

    @Test
    fun `잠근 뒤 작성자가 다를 때__수정하면__아무것도 쓰지 않고 거부한다`() {
        // given
        givenLockedRow()

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }

        // when
        val thrown = JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThatThrownBy { writer.update(42, 8, "수정", null, null) }
        }

        // then
        thrown.isInstanceOf(PostNotOwnedException::class.java)
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `행이 없거나 삭제됐을 때__잠그면__아무것도 쓰지 않고 404를 던진다`() {
        // given
        givenLockedRow()
        row.hasNext = false

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }

        // when
        val thrown = JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThatThrownBy { writer.update(42, 7, "수정", null, null) }
        }

        // then
        thrown.isInstanceOf(PostNotFoundException::class.java)
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `갱신 조건에서 업데이트를 잃을 때__쓰면__투영 없이 404를 던진다`() {
        // given
        givenLockedRow()
        updated = 0

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<PostSnapshot>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<PostSnapshot>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updated
        }

        // when
        val thrown = JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThatThrownBy { writer.update(42, 7, "수정", null, null) }
        }

        // then
        thrown.isInstanceOf(PostNotFoundException::class.java)
        assertThat(calls).hasSize(2)
    }

    /** Data builder: the row a locked, existing post would return. */
    private fun givenLockedRow() {
        row.longs["post_id"] = 42L
        row.longs["author_id"] = 7L
        row.strings["title"] = "제목"
        row.strings["content"] = "본문"
        row.strings["board_type"] = "MISSING"
        row.strings["image_urls"] = "[\"https://fixture.invalid/old\"]"
        row.timestamps["created_at"] = Timestamp.valueOf(previous)
        row.timestamps["updated_at"] = Timestamp.valueOf(previous)
    }
}
