package com.pawbridge.backend.user.internal.contact.infrastructure

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Collections
import java.util.stream.Stream

class JdbcContactMembersQueryTest {
    @ParameterizedTest
    @MethodSource("invalidLists")
    fun given__invalid_raw_ids_before_deduplication__when__querying__then__reject_outside_transaction_and_typed_failure_boundary(
        label: String,
        ids: List<Long?>?,
    ) {
        val fixture = Fixture()

        val failure = assertThrows(IllegalArgumentException::class.java) { fixture.query.getAll(ids) }

        assertEquals("회원 조회 범위를 확인해 주세요.", failure.message)
        assertEquals(0, fixture.transactions.begins)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertEquals(0, fixture.jdbc.queries)
        verifyNoInteractions(fixture.row)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__exactly_21_raw_ids__when__querying__then__accept_once_and_return_a_projection_for_each_first_occurring_id_after_commit(
        repeated: Boolean,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows.clear()
        val ids: List<Long> = if (repeated) Collections.nCopies(21, 7L) else (1L..21L).toList()
        val distinctIds: List<Long> = if (repeated) listOf(7L) else ids

        val found = fixture.query.getAll(ids)

        assertEquals(distinctIds.map { id -> ContactMember(id, "탈퇴한 회원", false, false) }, found)
        assertArrayEquals(distinctIds.toTypedArray(), fixture.jdbc.arguments)
        assertEquals(sql(distinctIds.size), fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim())
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__unordered_active_pending_missing_and_duplicate_ids__when__querying__then__restore_input_order_and_raw_nicknames_without_partial_fallback() {
        val fixture = Fixture()
        val whitespace = member(10, "  원문\n닉네임  ", false)
        val pending = member(7, "노출하지 않을 닉네임", true)
        val empty = member(9, "", false)
        fixture.jdbc.rows.clear()
        fixture.jdbc.rows.addAll(listOf(whitespace, pending, empty))
        val ids = ArrayList(listOf(9L, 7L, 9L, 8L, 10L))

        val found = fixture.query.getAll(ids)

        assertEquals(
            listOf(
                ContactMember(9L, "", true, false),
                ContactMember(7L, "탈퇴한 회원", false, true),
                ContactMember(8L, "탈퇴한 회원", false, false),
                ContactMember(10L, "  원문\n닉네임  ", true, false),
            ),
            found,
        )
        assertEquals(listOf(9L, 7L, 9L, 8L, 10L), ids)
        assertArrayEquals(arrayOf<Any?>(9L, 7L, 8L, 10L), fixture.jdbc.arguments)
        assertEquals(sql(4), fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim())
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        verify(pending, never()).getString("nickname")
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__batch_begin_query_mapping_or_commit_failure_then_recovery__when__querying__then__preserve_the_cause_as_typed_unavailable_without_partial_results(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.QUERY -> DataAccessResourceFailureException("synthetic-private-batch-query")
            FailurePhase.MAP -> IllegalStateException("synthetic-private-mapping")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY -> fixture.jdbc.failure = failure
            FailurePhase.MAP -> `when`(fixture.row.getBoolean("pending")).thenThrow(failure)
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        val unavailable = assertThrows(ContactMemberUnavailableException::class.java) { fixture.query.getAll(listOf(7L)) }

        assertSame(failure, unavailable.cause)
        assertEquals("Contact member lookup is unavailable.", unavailable.message)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.queries)
        assertEquals(if (phase == FailurePhase.QUERY || phase == FailurePhase.MAP) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null
        doReturn(false).`when`(fixture.row).getBoolean("pending")
        assertEquals(listOf(ContactMember(7L, "원문 닉네임", true, false)), fixture.query.getAll(listOf(7L)))
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY, MAP, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val row: ResultSet = member(7, "원문 닉네임", false)
        val jdbc = RecordingJdbc(transactions)
        val query: JdbcContactMembersQuery

        init {
            jdbc.rows.add(row)
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            query = JdbcContactMembersQuery(jdbc, transaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        val rows = ArrayList<ResultSet>()
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            assertTrue(transactions.active)
            this.sql = sql
            this.arguments = args
            queries++
            if (failure != null) {
                throw failure!!
            }
            val result = ArrayList<T>()
            try {
                for (index in 0 until rows.size) {
                    result.add(rowMapper.mapRow(rows[index], index)!!)
                }
            } catch (failure: SQLException) {
                throw UncategorizedSQLException("contact batch lookup", sql, failure)
            }
            return result
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var begins = 0
        var commits = 0
        var rollbacks = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            begins++
            if (startFailure != null) {
                throw startFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (completionFailure != null) {
                throw completionFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
        }
    }

    companion object {
        @JvmStatic
        fun invalidLists(): Stream<Arguments> = Stream.of(
            Arguments.of("null", null),
            Arguments.of("empty", listOf<Long>()),
            Arguments.of("22-distinct", (1L..22L).toList()),
            Arguments.of("22-duplicates", Collections.nCopies(22, 7L)),
            Arguments.of("null-element", listOf<Long?>(7L, null)),
            Arguments.of("zero", listOf(7L, 0L)),
            Arguments.of("negative", listOf(-1L)),
        )

        private fun sql(ids: Int): String {
            return "SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending FROM pawbridge_user.users u " +
                "LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id WHERE u.user_id IN (" +
                Collections.nCopies(ids, "?").joinToString(",") + ")"
        }

        private fun member(id: Long, nickname: String, pending: Boolean): ResultSet {
            val row = mock(ResultSet::class.java)
            `when`(row.getLong("user_id")).thenReturn(id)
            `when`(row.getString("nickname")).thenReturn(nickname)
            `when`(row.getBoolean("pending")).thenReturn(pending)
            return row
        }
    }
}
