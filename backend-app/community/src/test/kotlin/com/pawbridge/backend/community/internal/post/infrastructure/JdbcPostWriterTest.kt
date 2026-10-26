package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.TransactionSystemException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class JdbcPostWriterTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = FakeTransactions()
    private val calls = ArrayList<StatementCall>()
    private val connection = recordingConnection(calls) { assertTrue(transactions.active) }
    private val row = FakeRow()
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T03:30:10.123456789Z"), ZoneId.of("Asia/Seoul"))
    private var failAtStatement = 0
    private val writeFailure: RuntimeException = DataAccessResourceFailureException("synthetic write failure")

    @Test
    fun `게시글과 미디어가 있을 때__저장하면__식별자와 JSON과 가중 검색 투영이 아웃박스 없이 한 트랜잭션을 공유한다`() {
        // given
        row.longs["post_id"] = 42L

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAtStatement == calls.size) {
                throw writeFailure
            }
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAtStatement == calls.size) {
                throw writeFailure
            }
            1
        }

        // when
        val post: PostSnapshot
        writer().use { writer ->
            post = writer.create(input())
        }

        // then
        assertFalse(transactions.active)
        assertEquals(2, calls.size)
        assertTrue(calls.stream().noneMatch { call -> call.sql.contains("outbox_events") })
        val source = calls[0]
        assertTrue(source.sql.contains("pawbridge_community.posts"))
        assertTrue(source.sql.contains("RETURNING post_id"))
        assertEquals(7L, source.values[1])
        assertEquals("보호 동물", source.values[2])
        assertEquals("새 가족을 찾습니다", source.values[3])
        assertEquals("PROTECTION", source.values[4])
        assertEquals(mapper.valueToTree<JsonNode>(input().imageUrls()), mapper.readTree(source.values[5] as String))
        assertEquals(
            PostSnapshot(
                42L,
                7L,
                "보호 동물",
                "새 가족을 찾습니다",
                "PROTECTION",
                input().imageUrls(),
                LocalDateTime.now(clock),
                LocalDateTime.now(clock),
            ),
            post,
        )
        assertEquals(Timestamp.valueOf(post.createdAt()), source.values[6])
        assertEquals(Timestamp.valueOf(post.updatedAt()), source.values[7])
        assertEquals("UTC", source.calendars[6]!!.timeZone.id)
        assertEquals("UTC", source.calendars[7]!!.timeZone.id)

        val search = calls[1]
        assertTrue(search.sql.contains("pawbridge_community.post_search_documents"))
        assertTrue(search.sql.contains("md5(?)"))
        assertTrue(search.sql.contains("setweight(to_tsvector('simple', ?), 'A')"))
        assertTrue(search.sql.contains("setweight(to_tsvector('simple', ?), 'B')"))
        assertEquals(42L, search.values[1])
        assertEquals(input().title(), search.values[2])
        assertEquals(input().content(), search.values[3])
        assertEquals("nori-9.12.3-v1", search.values[4])
        assertFalse((search.values[5] as String).isBlank())
        assertFalse((search.values[6] as String).isBlank())
    }

    @Test
    fun `검색 문서 저장이 실패할 때__저장하면__성공을 돌려주지 않고 예외를 트랜잭션까지 전달한다`() {
        // given
        failAtStatement = 2
        row.longs["post_id"] = 42L

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAtStatement == calls.size) {
                throw writeFailure
            }
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAtStatement == calls.size) {
                throw writeFailure
            }
            1
        }

        // when
        writer().use { writer ->
            val thrown = assertThrows(RuntimeException::class.java) { writer.create(input()) }

            // then
            assertSame(writeFailure, thrown)
        }
        assertEquals(2, calls.size)
        assertFalse(transactions.active)
    }

    @Test
    fun `커밋에서 문자셋 검사가 실패할 때__저장하면__이름이 맞는 커뮤니티 제약만 사용자 오류로 바꾼다`() {
        // given
        val failure = TransactionSystemException(
            "commit",
            charset("23514", "pawbridge_community", "ck_rollback_charset_posts"),
        )
        transactions.failureBeforeCallback = failure

        // when
        writer().use { writer ->
            val translated = assertThrows(UnsupportedPostCharactersException::class.java) {
                writer.create(input())
            }

            // then
            assertSame(failure, translated.cause)
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", translated.message)
        }
    }

    @Test
    fun `커밋에서 관련 없는 검사 제약이 실패할 때__저장하면__원래 예외를 사용자 오류로 바꾸지 않는다`() {
        // given
        val failure = TransactionSystemException(
            "commit",
            charset("23514", "pawbridge_community", "business_constraint"),
        )
        transactions.failureBeforeCallback = failure

        // when
        writer().use { writer ->
            val thrown = assertThrows(TransactionSystemException::class.java) { writer.create(input()) }

            // then
            assertSame(failure, thrown)
        }
    }

    private fun writer(): JdbcPostWriter = JdbcPostWriter(jdbc, transactions, mapper, clock)

    private fun input(): PostToCreate =
        PostToCreate(
            7L,
            "보호 동물",
            "새 가족을 찾습니다",
            PostBoardType.PROTECTION,
            listOf("https://example.test/한글.png", "https://example.test/movie.mp4"),
        )

    private fun charset(state: String, schema: String, constraint: String): PSQLException =
        PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C" + state + "\u0000Msynthetic\u0000s" + schema + "\u0000n" + constraint + "\u0000",
            ),
        )
}
