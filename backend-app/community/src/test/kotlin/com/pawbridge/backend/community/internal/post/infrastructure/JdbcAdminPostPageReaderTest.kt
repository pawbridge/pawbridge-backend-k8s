package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.util.stream.Stream

class JdbcAdminPostPageReaderTest {
    private val jdbc = mockk<JdbcOperations>()
    private val tx = FakeTransactions()
    private val reader = JdbcPostReader(jdbc, tx, ObjectMapper())

    @ParameterizedTest
    @MethodSource("pages")
    fun `일부이거나 가득 찼거나 범위 밖인 페이지일 때__조회하면__스프링 데이터의 개수 생략과 단일 트랜잭션 범위를 유지한다`(
        page: Int,
        rows: Int,
        total: Long,
        count: Boolean,
    ) {
        // given
        val values = ArrayList<PostSnapshot>()
        for (n in 0 until rows) {
            values.add(PostSnapshot(n.toLong(), 7, "title", "content", "REPORT", listOf(), null, null))
        }

        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } answers {
            assertThat(tx.active).isTrue()
            assertThat(firstArg<String>()).contains(
                "FROM pawbridge_community.posts WHERE deleted_at IS NULL",
                "ORDER BY created_at DESC LIMIT ? OFFSET ?",
            )
            values
        }
        every { jdbc.queryForObject(any<String>(), Long::class.javaObjectType) } answers {
            assertThat(tx.active).isTrue()
            41L
        }

        // when
        val result = reader.findPage(
            PostPageReader.Request(page, 2, listOf(PostPageReader.Order("createdAt", false, false))),
        )

        // then
        assertThat(result.content()).isEqualTo(values)
        assertThat(result.total()).isEqualTo(total)
        assertThat(tx.active).isFalse()
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), 2, page.toLong() * 2) }
        verify(exactly = if (count) 1 else 0) {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_community.posts WHERE deleted_at IS NULL",
                Long::class.javaObjectType,
            )
        }
    }

    @Test
    fun `JPA 정수 오프셋 경계일 때__읽으면__정확히 한계는 허용하고 더 큰 오프셋만 SQL 없이 거부한다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } returns listOf()
        every { jdbc.queryForObject(any<String>(), Long::class.javaObjectType) } returns 0L

        // when
        reader.findPage(PostPageReader.Request(Integer.MAX_VALUE, 1, listOf()))

        // then
        verify(exactly = 1) {
            jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), 1, Integer.MAX_VALUE.toLong())
        }
        clearMocks(jdbc, answers = false)
        val rejected = assertThatThrownBy { reader.findPage(PostPageReader.Request(Integer.MAX_VALUE, 2, listOf())) }
        rejected
            .isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)")
        confirmVerified(jdbc)
        assertThat(tx.active).isFalse()
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `매핑된 정렬 속성마다__렌더링하면__고정 열과 JSON의 기본 null 순서만 쓰고 보조 정렬은 두지 않는다`(
        property: String,
        sqlColumn: String,
    ) {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } returns listOf()
        every { jdbc.queryForObject(any<String>(), Long::class.javaObjectType) } returns 0L

        // when
        reader.findPage(PostPageReader.Request(0, 20, listOf(PostPageReader.Order(property, true, true))))

        // then
        verify(exactly = 1) {
            jdbc.query(
                match<String> { it.contains(" ORDER BY $sqlColumn ASC LIMIT ? OFFSET ?") },
                any<RowMapper<PostSnapshot>>(),
                20,
                0L,
            )
        }
        assertThat(tx.active).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["authorNickname", "title;DELETE FROM posts", "unknown"])
    fun `매핑되지 않았거나 주입된 정렬일 때__읽으면__새 정렬 정책이나 식별자 보간 없이 SQL 전에 실패한다`(
        property: String,
    ) {
        // given
        val request = PostPageReader.Request(0, 20, listOf(PostPageReader.Order(property, true, false)))

        // when
        val thrown = assertThatThrownBy { reader.findPage(request) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
        confirmVerified(jdbc)
        assertThat(tx.executions).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "count"])
    fun `데이터베이스가 실패할 때__읽으면__트랜잭션이 풀린 뒤 예외를 전파한다`(phase: String) {
        // given
        val fail = DataAccessResourceFailureException("synthetic")

        if (phase == "query") {
            every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } throws fail
        } else {
            every { jdbc.query(any<String>(), any<RowMapper<PostSnapshot>>(), *anyVararg()) } returns listOf()
            every { jdbc.queryForObject(any<String>(), Long::class.javaObjectType) } throws fail
        }

        // when
        val thrown = assertThatThrownBy { reader.findPage(PostPageReader.Request(1, 2, listOf())) }

        // then
        thrown.isSameAs(fail)
        assertThat(tx.active).isFalse()
    }

    companion object {
        @JvmStatic
        fun pages(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0, 0, 0L, false),
                Arguments.of(0, 1, 1L, false),
                Arguments.of(1, 1, 3L, false),
                Arguments.of(0, 2, 41L, true),
                Arguments.of(4, 0, 41L, true),
            )

        @JvmStatic
        fun sorts(): Stream<Arguments> =
            Stream.of(
                Arguments.of("postId", "post_id"),
                Arguments.of("authorId", "author_id"),
                Arguments.of("title", "lower(title)"),
                Arguments.of("content", "lower(content)"),
                Arguments.of("boardType", "board_type"),
                Arguments.of("imageUrls", "image_urls"),
                Arguments.of("deletedAt", "deleted_at"),
                Arguments.of("createdAt", "created_at"),
                Arguments.of("updatedAt", "updated_at"),
            )
    }
}
