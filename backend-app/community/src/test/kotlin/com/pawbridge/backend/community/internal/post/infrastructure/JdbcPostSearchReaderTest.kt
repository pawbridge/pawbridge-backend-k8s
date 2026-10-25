package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import com.pawbridge.backend.community.support.userNicknameQuery
import org.junit.jupiter.api.AfterEach
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
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream

class JdbcPostSearchReaderTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val row = mock(ResultSet::class.java)
    private val reader = JdbcPostSearchReader(jdbc, transactions, ObjectMapper())
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
        whenever(
            jdbc.query(
                anyString(),
                any<RowMapper<PostSnapshot>>(),
                eq(KoreanSearchTerms.VERSION),
                anyString(),
                anyString(),
            ),
        ).thenAnswer { invocation ->
            assertTrue(transactionActive)
            assertEquals("'alpha' | 'beta'", invocation.getArgument<Any>(3))
            assertEquals(invocation.getArgument<String>(3), invocation.getArgument<String>(4))
            val mapper = invocation.getArgument<RowMapper<PostSnapshot>>(1)
            listOf(mapper.mapRow(row, 0))
        }
        whenever(row.getBoolean("ready")).thenReturn(true)
        whenever(row.getObject("post_id")).thenReturn(42L)
        whenever(row.getLong("post_id")).thenReturn(42L)
        whenever(row.getLong("author_id")).thenReturn(7L)
        whenever(row.getString("title")).thenReturn("검색 제목")
        whenever(row.getString("content")).thenReturn("검색 본문")
        whenever(row.getString("board_type")).thenReturn("REPORT")
        whenever(row.getString("image_urls")).thenReturn("[]")
        whenever(row.getObject("created_at", LocalDateTime::class.java)).thenReturn(createdAt)
        whenever(row.getObject("updated_at", LocalDateTime::class.java)).thenReturn(updatedAt)
    }

    @AfterEach
    fun closeReaderAnalyzer() {
        reader.close()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "!!!"])
    fun given__empty_query__when__search__then__skip_transaction_and_database(keyword: String?) {
        assertEquals(listOf<PostSnapshot>(), reader.search(keyword))
        verifyNoInteractions(transactions, jdbc)
    }

    @Test
    fun given__invalid_input__when__search__then__reject_before_transaction_or_database() {
        assertThrows(InvalidPostSearchInputException::class.java) { reader.search("x".repeat(201)) }
        verifyNoInteractions(transactions, jdbc)
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    fun given__ready_row__when__search__then__map_legacy_search_fields_and_local_date_time(
        stored: String?,
        expected: List<String?>?,
    ) {
        whenever(row.getString("image_urls")).thenReturn(stored)

        assertEquals(
            listOf(PostSnapshot(42L, 7L, "검색 제목", "검색 본문", "REPORT", expected, createdAt, updatedAt)),
            reader.search("alpha alpha beta"),
        )
        assertFalse(transactionActive)
        verify(jdbc).query(
            anyString(),
            any<RowMapper<PostSnapshot>>(),
            eq(KoreanSearchTerms.VERSION),
            anyString(),
            anyString(),
        )
    }

    @Test
    fun given__unready_projection__when__search__then__whole_result_is_unavailable() {
        whenever(row.getBoolean("ready")).thenReturn(false)

        assertThrows(PostSearchUnavailableException::class.java) { reader.search("alpha beta") }
        assertFalse(transactionActive)
        verify(row, never()).getObject("post_id")
    }

    @Test
    fun given__ready_without_ranked_row__when__search__then__return_empty_after_transaction() {
        whenever(row.getObject("post_id")).thenReturn(null)

        assertEquals(listOf<PostSnapshot>(), reader.search("alpha beta"))
        assertFalse(transactionActive)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{\"unexpected\":true}", "[{}]"])
    fun given__invalid_image_mapping__when__search__then__do_not_convert_failure_to_unavailable(stored: String) {
        whenever(row.getString("image_urls")).thenReturn(stored)

        assertThrows(IllegalStateException::class.java) { reader.search("alpha beta") }
        assertFalse(transactionActive)
    }

    @Test
    fun given__invalid_board_type__when__search__then__do_not_return_unvalidated_board_or_503() {
        whenever(row.getString("board_type")).thenReturn("UNKNOWN")

        assertThrows(IllegalArgumentException::class.java) { reader.search("alpha beta") }
        assertFalse(transactionActive)
    }

    @Test
    fun given__sql_failure__when__search__then__wrap_as_unavailable_after_transaction() {
        val failure = DataAccessResourceFailureException("private SQL detail")
        doThrow(failure).`when`(jdbc).query(
            anyString(),
            any<RowMapper<PostSnapshot>>(),
            eq(KoreanSearchTerms.VERSION),
            anyString(),
            anyString(),
        )

        assertSame(
            failure,
            assertThrows(PostSearchUnavailableException::class.java) { reader.search("alpha beta") }.cause,
        )
        assertFalse(transactionActive)
    }

    @ParameterizedTest
    @MethodSource("executionFailures")
    fun given__transaction_execution_failure__when__search__then__only_confirmed_unavailability_is_wrapped(
        failure: RuntimeException,
    ) {
        doThrow(failure).`when`(transactions).execute(any<TransactionCallback<Any>>())

        assertSame(
            failure,
            assertThrows(PostSearchUnavailableException::class.java) { reader.search("alpha beta") }.cause,
        )
        verifyNoInteractions(jdbc)
    }

    @Test
    fun given__unexpected_transaction_failure__when__search__then__original_failure_propagates() {
        val failure = TransactionSystemException("unexpected transaction error")
        doThrow(failure).`when`(transactions).execute(any<TransactionCallback<Any>>())

        assertSame(
            failure,
            assertThrows(TransactionSystemException::class.java) { reader.search("alpha beta") },
        )
    }

    @Test
    fun given__mapped_search__when__nickname_lookup_starts__then__read_transaction_has_finished() {
        val search = SearchPosts(
            reader,
            userNicknameQuery { authorId ->
                assertFalse(transactionActive)
                assertEquals(7L, authorId)
                Optional.of("작성자")
            },
        )

        assertEquals("작성자", search.search("alpha beta").first().authorNickname)
    }

    companion object {
        @JvmStatic
        fun imageUrls(): Stream<Arguments> =
            Stream.of(
                Arguments.of(null, listOf<String>()),
                Arguments.of("[]", listOf<String>()),
                Arguments.of("null", null),
                Arguments.of("[\"한글\",null,\"한글\"]", listOf("한글", null, "한글")),
            )

        @JvmStatic
        fun executionFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(CannotCreateTransactionException("unavailable")),
                Arguments.of(TransactionTimedOutException("timeout")),
            )
    }
}
