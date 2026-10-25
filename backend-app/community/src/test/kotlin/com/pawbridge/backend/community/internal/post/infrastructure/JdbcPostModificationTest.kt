package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
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

class JdbcPostModificationTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val tx = mock(TransactionOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val result = mock(ResultSet::class.java)
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T03:00:00.123456Z"), ZoneId.of("Asia/Seoul"))
    private val previous = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val calls = ArrayList<Call>()
    private var active = false
    private var exists = true
    private var images = "[\"https://fixture.invalid/old\"]"
    private var updated = 1

    @BeforeEach
    fun givenShortTransactionAndLockedRow() {
        whenever(tx.execute(any<TransactionCallback<PostSnapshot>>())).thenAnswer { call ->
            active = true
            try {
                call.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
        whenever(connection.prepareStatement(anyString())).thenAnswer { call ->
            assertThat(active).isTrue()
            val recorded = Call(call.getArgument(0))
            calls.add(recorded)
            val statement = mock(PreparedStatement::class.java)
            doAnswer { binding ->
                recorded.values[binding.getArgument(0)] = binding.getArgument(1)
                null
            }.`when`(statement).setObject(anyInt(), any())
            doAnswer { binding ->
                recorded.values[binding.getArgument(0)] = binding.getArgument(1)
                assertThat(binding.getArgument<Calendar>(2).timeZone.id).isEqualTo("UTC")
                null
            }.`when`(statement).setTimestamp(anyInt(), any(Timestamp::class.java), any(Calendar::class.java))
            statement
        }
        whenever(result.next()).thenAnswer { exists }
        whenever(result.getLong("post_id")).thenReturn(42L)
        whenever(result.getLong("author_id")).thenReturn(7L)
        whenever(result.getString(anyString())).thenAnswer { call ->
            when (call.getArgument<String>(0)) {
                "title" -> "제목"
                "content" -> "본문"
                "board_type" -> "MISSING"
                "image_urls" -> images
                else -> null
            }
        }
        whenever(result.getTimestamp(anyString(), any(Calendar::class.java))).thenReturn(Timestamp.valueOf(previous))
        whenever(jdbc.query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<PostSnapshot>>()))
            .thenAnswer { call ->
                call.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
                call.getArgument<ResultSetExtractor<*>>(1).extractData(result)
            }
        whenever(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer { call ->
            call.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            updated
        }
    }

    @Test
    fun given__partial_title__when__updating__then__lock_merge_emit_exact_payload_and_project_in_one_transaction() {
        val saved: PostSnapshot
        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            saved = writer.update(42, 7, "수정", null, null)
        }

        assertThat(active).isFalse()
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
    fun given__omitted_or_identical_values__when__updating__then__retain_row_timestamp_but_still_refresh_projection_without_outbox(
        same: Boolean,
    ) {
        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThat(
                writer.update(
                    42,
                    7,
                    if (same) "제목" else null,
                    if (same) "본문" else null,
                    if (same) listOf("https://fixture.invalid/old") else null,
                ).updatedAt(),
            ).isEqualTo(previous)
        }

        assertThat(calls).hasSize(2)
        assertThat(calls[1].sql).contains("post_search_documents")
        assertThat(calls).noneMatch { c -> c.sql.contains("outbox_events") }
    }

    @Test
    fun given__explicit_empty_fields_and_images__when__updating__then__write_empty_values_without_creation_board_restriction() {
        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            val saved = writer.update(42, 7, "", "", listOf())
            assertThat(saved.title()).isEmpty()
            assertThat(saved.content()).isEmpty()
            assertThat(saved.imageUrls()).isEmpty()
            assertThat(saved.boardType()).isEqualTo("MISSING")
        }

        assertThat(calls[1].values).containsEntry(1, "").containsEntry(2, "").containsEntry(3, "[]")
    }

    // The former NullPointerException came only from building the POST_UPDATED outbox payload with Map.of; with no outbox it no longer fails.
    @Test
    fun given__json_literal_null_images__when__text_only_update__then__succeed_without_outbox_statement() {
        images = "null"

        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThat(writer.update(42, 7, "수정", null, null).imageUrls()).isNull()
        }

        assertThat(calls).noneMatch { c -> c.sql.contains("outbox_events") }
    }

    @Test
    fun given__foreign_author_after_lock__when__updating__then__deny_without_any_write() {
        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThatThrownBy { writer.update(42, 8, "수정", null, null) }
                .isInstanceOf(PostNotOwnedException::class.java)
        }

        assertThat(calls).hasSize(1)
    }

    @Test
    fun given__missing_or_deleted_row__when__locking__then__404_without_any_write() {
        exists = false

        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThatThrownBy { writer.update(42, 7, "수정", null, null) }
                .isInstanceOf(PostNotFoundException::class.java)
        }

        assertThat(calls).hasSize(1)
    }

    @Test
    fun given__lost_update_predicate__when__writing__then__404_without_projection() {
        updated = 0

        JdbcPostWriter(jdbc, tx, mapper, clock).use { writer ->
            assertThatThrownBy { writer.update(42, 7, "수정", null, null) }
                .isInstanceOf(PostNotFoundException::class.java)
        }

        assertThat(calls).hasSize(2)
    }

    private class Call(val sql: String) {
        val values = LinkedHashMap<Int, Any?>()
    }
}
