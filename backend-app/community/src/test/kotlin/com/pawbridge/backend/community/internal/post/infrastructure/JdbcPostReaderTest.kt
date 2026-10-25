package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.support.userNicknameQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream

class JdbcPostReaderTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = FakeTransactions()
    private val row = FakeRow()
    private val reader = JdbcPostReader(jdbc, transactions, ObjectMapper())
    private val createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000)
    private val updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000)

    @Test
    fun `게시글 행이 있을 때__조회하면__트랜잭션이 끝나기 전에 스냅샷을 모두 매핑한다`() {
        // given
        givenPostRow("[]")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val post = reader.findById(42L).orElseThrow()

        // then
        assertEquals(
            PostSnapshot(42L, 7L, "함께 찾아주세요", "게시글 본문", "REPORT", listOf(), createdAt, updatedAt),
            post,
        )
        assertFalse(transactions.active)
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    fun `저장된 이미지 URL이 있을 때__조회하면__기존의 null과 순서와 중복을 보존한다`(
        stored: String?,
        expected: List<String?>?,
    ) {
        // given
        givenPostRow(stored)

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val imageUrls = reader.findById(42L).orElseThrow().imageUrls()

        // then
        assertEquals(expected, imageUrls)
        assertFalse(transactions.active)
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    fun `게시글 행들이 있을 때__전체를 조회하면__기존 필드와 이미지 매핑을 재사용한다`(
        stored: String?,
        expected: List<String?>?,
    ) {
        // given
        givenPostRow(stored)

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>()) } answers {
            assertTrue(transactions.active)
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val posts = reader.findAll()

        // then
        assertEquals(
            listOf(PostSnapshot(42L, 7L, "함께 찾아주세요", "게시글 본문", "REPORT", expected, createdAt, updatedAt)),
            posts,
        )
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>()) }
    }

    @Test
    fun `목록 조회기일 때__닉네임을 조회하면__게시글 매핑 트랜잭션은 이미 끝나 있다`() {
        // given
        givenPostRow("[]")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>()) } answers {
            assertTrue(transactions.active)
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }
        val listPosts = ListPosts(
            reader,
            userNicknameQuery { authorId ->
                assertFalse(transactions.active)
                assertEquals(7L, authorId)
                Optional.of("반려친구")
            },
        )

        // when
        val details = listPosts.read()

        // then
        assertEquals("반려친구", details.first().authorNickname)
        assertFalse(transactions.active)
    }

    @Test
    fun `게시글 행이 없을 때__전체를 조회하면__트랜잭션이 끝난 뒤 빈 목록을 돌려준다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>()) } returns listOf()

        // when
        val posts = reader.findAll()

        // then
        assertEquals(listOf<PostSnapshot>(), posts)
        assertFalse(transactions.active)
    }

    @Test
    fun `목록의 이미지 JSON이 잘못됐을 때__전체를 조회하면__매핑 실패가 트랜잭션을 벗어난다`() {
        // given
        givenPostRow("{\"unexpected\":\"shape\"}")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>()) } answers {
            assertTrue(transactions.active)
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { reader.findAll() }

        // then
        assertEquals(IllegalStateException::class.java, thrown.javaClass)
        assertFalse(transactions.active)
    }

    @Test
    fun `목록 조회가 실패할 때__전체를 조회하면__트랜잭션이 끝난 뒤 원래 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("unavailable")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { reader.findAll() }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
    }

    @Test
    fun `게시글 행이 없을 때__조회하면__트랜잭션이 끝난 뒤 빈 값을 돌려준다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } returns listOf()

        // when
        val post = reader.findById(42L)

        // then
        assertEquals(Optional.empty<PostSnapshot>(), post)
        assertFalse(transactions.active)
    }

    @Test
    fun `이미지 JSON이 잘못됐을 때__조회하면__매핑 실패가 트랜잭션을 벗어난다`() {
        // given
        givenPostRow("{\"unexpected\":\"shape\"}")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            listOf(secondArg<RowMapper<PostSnapshot>>().mapRow(row.resultSet, 0))
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { reader.findById(42L) }

        // then
        assertEquals(IllegalStateException::class.java, thrown.javaClass)
        assertFalse(transactions.active)
    }

    @Test
    fun `조회가 실패할 때__조회하면__트랜잭션이 끝난 뒤 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("unavailable")

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { reader.findById(42L) }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
    }

    /** Data builder: the columns of the one post row these tests read. */
    private fun givenPostRow(imageUrls: String?) {
        row.longs["post_id"] = 42L
        row.longs["author_id"] = 7L
        row.strings["title"] = "함께 찾아주세요"
        row.strings["content"] = "게시글 본문"
        row.strings["board_type"] = "REPORT"
        row.strings["image_urls"] = imageUrls
        row.timestamps["created_at"] = Timestamp.valueOf(createdAt)
        row.timestamps["updated_at"] = Timestamp.valueOf(updatedAt)
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
