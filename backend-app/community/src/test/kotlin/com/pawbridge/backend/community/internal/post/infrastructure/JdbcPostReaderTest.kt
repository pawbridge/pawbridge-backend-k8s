package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.support.userNicknameQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Optional
import java.util.stream.Stream

class JdbcPostReaderTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val row = mock(ResultSet::class.java)
    private val reader = JdbcPostReader(jdbc, transactions, ObjectMapper())
    private val createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000)
    private val updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000)
    private var transactionActive = false

    @BeforeEach
    fun prepareTransactionAndRow() {
        whenever(transactions.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            transactionActive = true
            try {
                callback.doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(jdbc.query(anyString(), any<RowMapper<PostSnapshot>>(), eq(42L))).thenAnswer { invocation ->
            assertTrue(transactionActive)
            val mapper = invocation.getArgument<RowMapper<PostSnapshot>>(1)
            listOf(mapper.mapRow(row, 0))
        }
        whenever(row.getLong("post_id")).thenReturn(42L)
        whenever(row.getLong("author_id")).thenReturn(7L)
        whenever(row.getString("title")).thenReturn("함께 찾아주세요")
        whenever(row.getString("content")).thenReturn("게시글 본문")
        whenever(row.getString("board_type")).thenReturn("REPORT")
        whenever(row.getString("image_urls")).thenReturn("[]")
        whenever(row.getTimestamp(eq("created_at"), any(Calendar::class.java))).thenAnswer { invocation ->
            assertEquals("UTC", invocation.getArgument<Calendar>(1).timeZone.id)
            Timestamp.valueOf(createdAt)
        }
        whenever(row.getTimestamp(eq("updated_at"), any(Calendar::class.java))).thenAnswer { invocation ->
            assertEquals("UTC", invocation.getArgument<Calendar>(1).timeZone.id)
            Timestamp.valueOf(updatedAt)
        }
    }

    @Test
    fun given__post_row__when__find__then__snapshot_is_fully_mapped_before_transaction_returns() {
        val post = reader.findById(42L).orElseThrow()

        assertEquals(
            PostSnapshot(42L, 7L, "함께 찾아주세요", "게시글 본문", "REPORT", listOf(), createdAt, updatedAt),
            post,
        )
        assertFalse(transactionActive)
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    fun given__stored_image_urls__when__find__then__legacy_null_order_and_duplicates_are_preserved(
        stored: String?,
        expected: List<String?>?,
    ) {
        whenever(row.getString("image_urls")).thenReturn(stored)

        assertEquals(expected, reader.findById(42L).orElseThrow().imageUrls())
        assertFalse(transactionActive)
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    fun given__post_rows__when__find_all__then__existing_field_and_image_mapping_is_reused(
        stored: String?,
        expected: List<String?>?,
    ) {
        whenever(row.getString("image_urls")).thenReturn(stored)
        prepareListQuery()

        assertEquals(
            listOf(PostSnapshot(42L, 7L, "함께 찾아주세요", "게시글 본문", "REPORT", expected, createdAt, updatedAt)),
            reader.findAll(),
        )
        assertFalse(transactionActive)
        verify(jdbc).query(anyString(), any<RowMapper<PostSnapshot>>())
    }

    @Test
    fun given__list_reader__when__nickname_is_queried__then__post_mapping_transaction_has_completed() {
        prepareListQuery()
        val listPosts = ListPosts(
            reader,
            userNicknameQuery { authorId ->
                assertFalse(transactionActive)
                assertEquals(7L, authorId)
                Optional.of("반려친구")
            },
        )

        assertEquals("반려친구", listPosts.read().first().authorNickname)
        assertFalse(transactionActive)
    }

    @Test
    fun given__no_post_rows__when__find_all__then__empty_list_is_returned_after_transaction() {
        doReturn(listOf<PostSnapshot>()).`when`(jdbc).query(anyString(), any<RowMapper<PostSnapshot>>())

        assertEquals(listOf<PostSnapshot>(), reader.findAll())
        assertFalse(transactionActive)
    }

    @Test
    fun given__invalid_list_image_json__when__find_all__then__mapping_failure_leaves_transaction() {
        prepareListQuery()
        whenever(row.getString("image_urls")).thenReturn("{\"unexpected\":\"shape\"}")

        assertThrows(IllegalStateException::class.java) { reader.findAll() }
        assertFalse(transactionActive)
    }

    @Test
    fun given__list_query_failure__when__find_all__then__original_failure_propagates_after_transaction() {
        val failure = DataAccessResourceFailureException("unavailable")
        doThrow(failure).`when`(jdbc).query(anyString(), any<RowMapper<PostSnapshot>>())

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { reader.findAll() },
        )
        assertFalse(transactionActive)
    }

    private fun prepareListQuery() {
        whenever(jdbc.query(anyString(), any<RowMapper<PostSnapshot>>())).thenAnswer { invocation ->
            assertTrue(transactionActive)
            val mapper = invocation.getArgument<RowMapper<PostSnapshot>>(1)
            listOf(mapper.mapRow(row, 0))
        }
    }

    @Test
    fun given__no_post_row__when__find__then__empty_is_returned_after_transaction() {
        doReturn(listOf<PostSnapshot>()).`when`(jdbc)
            .query(anyString(), any<RowMapper<PostSnapshot>>(), eq(42L))

        assertEquals(Optional.empty<PostSnapshot>(), reader.findById(42L))
        assertFalse(transactionActive)
    }

    @Test
    fun given__invalid_image_json__when__find__then__mapping_failure_leaves_transaction() {
        whenever(row.getString("image_urls")).thenReturn("{\"unexpected\":\"shape\"}")

        assertThrows(IllegalStateException::class.java) { reader.findById(42L) }
        assertFalse(transactionActive)
    }

    @Test
    fun given__query_failure__when__find__then__failure_propagates_after_transaction() {
        val failure = DataAccessResourceFailureException("unavailable")
        doThrow(failure).`when`(jdbc)
            .query(anyString(), any<RowMapper<PostSnapshot>>(), eq(42L))

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { reader.findById(42L) },
        )
        assertFalse(transactionActive)
    }

    companion object {
        @JvmStatic
        fun imageUrls(): Stream<Arguments> =
            Stream.of(
                Arguments.of(null, listOf<String>()),
                Arguments.of("[]", listOf<String>()),
                Arguments.of("null", null),
                Arguments.of(
                    "[\"https://example.test/한글.png\",null,\"https://example.test/한글.png\"]",
                    listOf("https://example.test/한글.png", null, "https://example.test/한글.png"),
                ),
            )
    }
}
