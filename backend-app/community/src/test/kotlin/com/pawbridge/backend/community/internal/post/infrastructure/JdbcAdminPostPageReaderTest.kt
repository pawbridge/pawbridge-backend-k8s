package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.util.stream.Stream

class JdbcAdminPostPageReaderTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val tx = mock(TransactionOperations::class.java)
    private val reader = JdbcPostReader(jdbc, tx, ObjectMapper())
    private var active = false

    @BeforeEach
    fun transaction() {
        whenever(tx.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            active = true
            try {
                invocation.getArgument<TransactionCallback<Any>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
    }

    @ParameterizedTest
    @MethodSource("pages")
    fun given__partial_full_or_outside_page__when__querying__then__retain_spring_data_count_elision_and_scoped_one_transaction(
        page: Int,
        rows: Int,
        total: Long,
        count: Boolean,
    ) {
        val values = ArrayList<PostSnapshot>()
        for (n in 0 until rows) {
            values.add(PostSnapshot(n.toLong(), 7, "title", "content", "REPORT", listOf(), null, null))
        }
        whenever(jdbc.query(anyString(), any<RowMapper<PostSnapshot>>(), eq(2), eq(page.toLong() * 2)))
            .thenAnswer { invocation ->
                assertThat(active).isTrue()
                val sql = invocation.getArgument<String>(0)
                assertThat(sql).contains(
                    "FROM pawbridge_community.posts WHERE deleted_at IS NULL",
                    "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                )
                values
            }
        whenever(
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_community.posts WHERE deleted_at IS NULL",
                Long::class.javaObjectType,
            ),
        ).thenAnswer {
            assertThat(active).isTrue()
            41L
        }

        val result = reader.findPage(
            PostPageReader.Request(page, 2, listOf(PostPageReader.Order("createdAt", false, false))),
        )

        assertThat(result.content()).isEqualTo(values)
        assertThat(result.total()).isEqualTo(total)
        assertThat(active).isFalse()
        verify(jdbc, times(if (count) 1 else 0)).queryForObject(anyString(), eq(Long::class.javaObjectType))
    }

    @Test
    fun given__jpa_integer_offset_boundary__when__reading__then__allow_exact_limit_and_reject_only_larger_offset_without_sql() {
        doReturn(listOf<PostSnapshot>()).`when`(jdbc)
            .query(anyString(), any<RowMapper<PostSnapshot>>(), eq(1), eq(Integer.MAX_VALUE.toLong()))
        whenever(jdbc.queryForObject(anyString(), eq(Long::class.javaObjectType))).thenReturn(0L)

        reader.findPage(PostPageReader.Request(Integer.MAX_VALUE, 1, listOf()))

        verify(jdbc).query(anyString(), any<RowMapper<PostSnapshot>>(), eq(1), eq(Integer.MAX_VALUE.toLong()))
        clearInvocations(jdbc)
        assertThatThrownBy { reader.findPage(PostPageReader.Request(Integer.MAX_VALUE, 2, listOf())) }
            .isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)")
        verifyNoInteractions(jdbc)
        assertThat(active).isFalse()
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun given__each_mapped_sort_property__when__rendering__then__use_only_fixed_columns_and_native_null_json_order_without_tiebreak(
        property: String,
        sqlColumn: String,
    ) {
        doReturn(listOf<PostSnapshot>()).`when`(jdbc)
            .query(anyString(), any<RowMapper<PostSnapshot>>(), eq(20), eq(0L))

        reader.findPage(PostPageReader.Request(0, 20, listOf(PostPageReader.Order(property, true, true))))

        verify(jdbc).query(
            contains(" ORDER BY $sqlColumn ASC LIMIT ? OFFSET ?"),
            any<RowMapper<PostSnapshot>>(),
            eq(20),
            eq(0L),
        )
        assertThat(active).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["authorNickname", "title;DELETE FROM posts", "unknown"])
    fun given__unmapped_or_injected_sort__when__reading__then__fail_without_sql_instead_of_new_sort_policy_or_identifier_interpolation(
        property: String,
    ) {
        assertThatThrownBy {
            reader.findPage(PostPageReader.Request(0, 20, listOf(PostPageReader.Order(property, true, false))))
        }.isInstanceOf(IllegalArgumentException::class.java)
        verifyNoInteractions(jdbc, tx)
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "count"])
    fun given__database_failure__when__reading__then__propagate_it_after_transaction_unwinds(phase: String) {
        val fail = DataAccessResourceFailureException("synthetic")
        if (phase == "query") {
            doThrow(fail).`when`(jdbc).query(anyString(), any<RowMapper<PostSnapshot>>(), eq(2), eq(2L))
        } else {
            doReturn(listOf<PostSnapshot>()).`when`(jdbc)
                .query(anyString(), any<RowMapper<PostSnapshot>>(), eq(2), eq(2L))
            whenever(jdbc.queryForObject(anyString(), eq(Long::class.javaObjectType))).thenThrow(fail)
        }

        assertThatThrownBy { reader.findPage(PostPageReader.Request(1, 2, listOf())) }.isSameAs(fail)
        assertThat(active).isFalse()
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
