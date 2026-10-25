package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import com.pawbridge.backend.community.support.userNicknameQuery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream

class JdbcPostSearchReaderTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = FakeTransactions()
    private val row = FakeRow()
    private val reader = JdbcPostSearchReader(jdbc, transactions, ObjectMapper())
    private val createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000)
    private val updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000)

    @AfterEach
    fun closeReaderAnalyzer() {
        reader.close()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "!!!"])
    fun `질의가 비었을 때__검색하면__트랜잭션과 데이터베이스를 건너뛴다`(keyword: String?) {
        // given
        val input = keyword

        // when
        val result = reader.search(input)

        // then
        assertEquals(listOf<PostSnapshot>(), result)
        assertEquals(0, transactions.executions)
        confirmVerified(jdbc)
    }

    @Test
    fun `입력이 잘못됐을 때__검색하면__트랜잭션이나 데이터베이스 전에 거부한다`() {
        // given
        val tooLong = "x".repeat(201)

        // when
        val thrown = assertThrows(InvalidPostSearchInputException::class.java) { reader.search(tooLong) }

        // then
        assertEquals(InvalidPostSearchInputException::class.java, thrown.javaClass)
        assertEquals(0, transactions.executions)
        confirmVerified(jdbc)
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    fun `준비된 행이 있을 때__검색하면__기존 검색 필드와 LocalDateTime으로 매핑한다`(
        stored: String?,
        expected: List<String?>?,
    ) {
        // given
        givenReadyRow(stored)

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val bound = args[2] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            assertEquals("'alpha' | 'beta'", bound[1])
            assertEquals(bound[1], bound[2])
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val posts = reader.search("alpha alpha beta")

        // then
        assertEquals(
            listOf(PostSnapshot(42L, 7L, "검색 제목", "검색 본문", "REPORT", expected, createdAt, updatedAt)),
            posts,
        )
        assertFalse(transactions.active)
        verify(exactly = 1) {
            jdbc.query(
                any<String>(),
                any<RowMapper<PostSnapshot>>(),
                KoreanSearchTerms.VERSION,
                "'alpha' | 'beta'",
                "'alpha' | 'beta'",
            )
        }
    }

    @Test
    fun `투영이 준비되지 않았을 때__검색하면__결과 전체를 사용할 수 없음으로 처리한다`() {
        // given
        givenReadyRow("[]")
        row.booleans["ready"] = false

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val bound = args[2] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            assertEquals("'alpha' | 'beta'", bound[1])
            assertEquals(bound[1], bound[2])
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val thrown = assertThrows(PostSearchUnavailableException::class.java) { reader.search("alpha beta") }

        // then
        assertEquals(PostSearchUnavailableException::class.java, thrown.javaClass)
        assertFalse(transactions.active)
        assertFalse(row.reads.contains("post_id"))
    }

    @Test
    fun `준비됐지만 순위에 든 행이 없을 때__검색하면__트랜잭션이 끝난 뒤 빈 목록을 돌려준다`() {
        // given
        givenReadyRow("[]")
        row.objects["post_id"] = null

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val bound = args[2] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            assertEquals("'alpha' | 'beta'", bound[1])
            assertEquals(bound[1], bound[2])
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val posts = reader.search("alpha beta")

        // then
        assertEquals(listOf<PostSnapshot>(), posts)
        assertFalse(transactions.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{\"unexpected\":true}", "[{}]"])
    fun `이미지 매핑이 잘못됐을 때__검색하면__실패를 사용할 수 없음으로 바꾸지 않는다`(stored: String) {
        // given
        givenReadyRow(stored)

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val bound = args[2] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            assertEquals("'alpha' | 'beta'", bound[1])
            assertEquals(bound[1], bound[2])
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { reader.search("alpha beta") }

        // then
        assertEquals(IllegalStateException::class.java, thrown.javaClass)
        assertFalse(transactions.active)
    }

    @Test
    fun `게시판 유형이 잘못됐을 때__검색하면__검증되지 않은 게시판이나 503을 돌려주지 않는다`() {
        // given
        givenReadyRow("[]")
        row.strings["board_type"] = "UNKNOWN"

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val bound = args[2] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            assertEquals("'alpha' | 'beta'", bound[1])
            assertEquals(bound[1], bound[2])
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { reader.search("alpha beta") }

        // then
        assertEquals(IllegalArgumentException::class.java, thrown.javaClass)
        assertFalse(transactions.active)
    }

    @Test
    fun `SQL이 실패할 때__검색하면__트랜잭션이 끝난 뒤 사용할 수 없음으로 감싼다`() {
        // given
        val failure = DataAccessResourceFailureException("private SQL detail")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } throws failure

        // when
        val thrown = assertThrows(PostSearchUnavailableException::class.java) { reader.search("alpha beta") }

        // then
        assertSame(failure, thrown.cause)
        assertFalse(transactions.active)
    }

    @ParameterizedTest
    @MethodSource("executionFailures")
    fun `트랜잭션 실행이 실패할 때__검색하면__확인된 사용 불가만 감싼다`(
        failure: RuntimeException,
    ) {
        // given
        transactions.failureBeforeCallback = failure

        // when
        val thrown = assertThrows(PostSearchUnavailableException::class.java) { reader.search("alpha beta") }

        // then
        assertSame(failure, thrown.cause)
        confirmVerified(jdbc)
    }

    @Test
    fun `예상 밖의 트랜잭션 실패일 때__검색하면__원래 예외를 전파한다`() {
        // given
        val failure = TransactionSystemException("unexpected transaction error")
        transactions.failureBeforeCallback = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { reader.search("alpha beta") }

        // then
        assertSame(failure, thrown)
    }

    @Test
    fun `매핑된 검색일 때__닉네임 조회가 시작되면__읽기 트랜잭션은 이미 끝나 있다`() {
        // given
        givenReadyRow("[]")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val bound = args[2] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            assertEquals("'alpha' | 'beta'", bound[1])
            assertEquals(bound[1], bound[2])
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }
        val search = SearchPosts(
            reader,
            userNicknameQuery { authorId ->
                assertFalse(transactions.active)
                assertEquals(7L, authorId)
                Optional.of("작성자")
            },
        )

        // when
        val details = search.search("alpha beta")

        // then
        assertEquals("작성자", details.first().authorNickname)
    }

    /** Data builder: the columns of the one ready, ranked search row these tests read. */
    private fun givenReadyRow(imageUrls: String?) {
        row.booleans["ready"] = true
        row.objects["post_id"] = 42L
        row.longs["post_id"] = 42L
        row.longs["author_id"] = 7L
        row.strings["title"] = "검색 제목"
        row.strings["content"] = "검색 본문"
        row.strings["board_type"] = "REPORT"
        row.strings["image_urls"] = imageUrls
        row.objects["created_at"] = createdAt
        row.objects["updated_at"] = updatedAt
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
