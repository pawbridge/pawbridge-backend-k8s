package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

class JdbcPostWriterTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val result = mock(ResultSet::class.java)
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T03:30:10.123456789Z"), ZoneId.of("Asia/Seoul"))
    private val calls = ArrayList<StatementCall>()
    private var transactionActive = false
    private var failAtStatement = 0
    private val writeFailure: RuntimeException = DataAccessResourceFailureException("synthetic write failure")

    @BeforeEach
    fun prepareTransactionAndJdbc() {
        whenever(transactions.execute(any<TransactionCallback<PostSnapshot>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<PostSnapshot>>(0)
            transactionActive = true
            try {
                callback.doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(connection.prepareStatement(anyString())).thenAnswer { invocation ->
            assertTrue(transactionActive)
            val call = StatementCall(invocation.getArgument(0))
            calls.add(call)
            val statement = mock(PreparedStatement::class.java)
            doAnswer { binding ->
                call.values[binding.getArgument(0)] = binding.getArgument(1)
                null
            }.`when`(statement).setObject(anyInt(), any())
            doAnswer { binding ->
                call.values[binding.getArgument(0)] = binding.getArgument(1)
                call.calendars[binding.getArgument(0)] = binding.getArgument(2)
                null
            }.`when`(statement).setTimestamp(anyInt(), any(Timestamp::class.java), any(Calendar::class.java))
            statement
        }
        whenever(result.next()).thenReturn(true)
        whenever(result.getLong("post_id")).thenReturn(42L)
        whenever(jdbc.query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>()))
            .thenAnswer { invocation ->
                invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
                if (failAtStatement == calls.size) {
                    throw writeFailure
                }
                invocation.getArgument<ResultSetExtractor<*>>(1).extractData(result)
            }
        whenever(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer { invocation ->
            invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            if (failAtStatement == calls.size) {
                throw writeFailure
            }
            1
        }
    }

    @Test
    fun given__post_and_media__when__write__then__identity_json_and_weighted_projection_share_transaction_without_outbox() {
        val post: PostSnapshot
        writer().use { writer ->
            post = writer.create(input())
        }

        assertFalse(transactionActive)
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
    fun given__search_failure__when__write__then__failure_reaches_transaction_without_returning_success() {
        failAtStatement = 2
        writer().use { writer ->
            assertSame(writeFailure, assertThrows(RuntimeException::class.java) { writer.create(input()) })
        }
        assertEquals(2, calls.size)
        assertFalse(transactionActive)
    }

    @Test
    fun given__commit_charset_failure__when__write__then__only_named_community_constraint_becomes_user_error() {
        val failure = TransactionSystemException(
            "commit",
            charset("23514", "pawbridge_community", "ck_rollback_charset_posts"),
        )
        doThrow(failure).`when`(transactions).execute(any<TransactionCallback<PostSnapshot>>())
        writer().use { writer ->
            val translated = assertThrows(UnsupportedPostCharactersException::class.java) {
                writer.create(input())
            }
            assertSame(failure, translated.cause)
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", translated.message)
        }
    }

    @Test
    fun given__unrelated_check_failure_at_commit__when__write__then__original_failure_is_not_a_user_error() {
        val failure = TransactionSystemException(
            "commit",
            charset("23514", "pawbridge_community", "business_constraint"),
        )
        doThrow(failure).`when`(transactions).execute(any<TransactionCallback<PostSnapshot>>())
        writer().use { writer ->
            assertSame(failure, assertThrows(TransactionSystemException::class.java) { writer.create(input()) })
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

    private class StatementCall(val sql: String) {
        val values = LinkedHashMap<Int, Any?>()
        val calendars = LinkedHashMap<Int, Calendar>()
    }
}
