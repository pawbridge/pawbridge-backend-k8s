package com.pawbridge.backend.user.internal.contact.infrastructure

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `중복 제거 전의 원본 ID가 올바르지 않을 때__조회하면__트랜잭션 밖에서 거부하고 유형 있는 실패 경계를 타지 않는다`(
        label: String,
        ids: List<Long?>?,
    ) {
        // given
        val fixture = Fixture()

        // when
        val failure = assertThrows(IllegalArgumentException::class.java) { fixture.query.getAll(ids) }

        // then
        assertEquals("회원 조회 범위를 확인해 주세요.", failure.message)
        assertEquals(0, fixture.transactions.begins)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertEquals(0, fixture.jdbc.queries)
        confirmVerified(fixture.row)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `원본 ID가 정확히 21개일 때__조회하면__한 번 받아들이고 처음 나온 ID마다 커밋 뒤 투영을 돌려준다`(
        repeated: Boolean,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows.clear()
        val ids: List<Long> = if (repeated) Collections.nCopies(21, 7L) else (1L..21L).toList()
        val distinctIds: List<Long> = if (repeated) listOf(7L) else ids

        // when
        val found = fixture.query.getAll(ids)

        // then
        assertEquals(distinctIds.map { id -> ContactMember(id, "탈퇴한 회원", false, false) }, found)
        assertArrayEquals(distinctIds.toTypedArray(), fixture.jdbc.arguments)
        assertEquals(sql(distinctIds.size), fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim())
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `정렬되지 않은 활성과 삭제 대기와 없는 중복 ID일 때__조회하면__입력 순서와 원문 닉네임을 복원하고 부분 대체값을 쓰지 않는다`() {
        // given
        val fixture = Fixture()
        val whitespace = mockk<ResultSet>()
        val pending = mockk<ResultSet>()
        val empty = mockk<ResultSet>()
        fixture.jdbc.rows.clear()
        fixture.jdbc.rows.addAll(listOf(whitespace, pending, empty))
        val ids = ArrayList(listOf(9L, 7L, 9L, 8L, 10L))

        every { whitespace.getLong("user_id") } returns 10L
        every { whitespace.getString("nickname") } returns "  원문\n닉네임  "
        every { whitespace.getBoolean("pending") } returns false
        every { pending.getLong("user_id") } returns 7L
        every { pending.getString("nickname") } returns "노출하지 않을 닉네임"
        every { pending.getBoolean("pending") } returns true
        every { empty.getLong("user_id") } returns 9L
        every { empty.getString("nickname") } returns ""
        every { empty.getBoolean("pending") } returns false

        // when
        val found = fixture.query.getAll(ids)

        // then
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
        verify(exactly = 0) { pending.getString("nickname") }
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `배치 시작과 조회와 매핑과 커밋이 실패한 뒤 복구될 때__조회하면__부분 결과 없이 원인을 유형 있는 사용 불가로 보존한다`(
        phase: FailurePhase,
    ) {
        // given
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
            FailurePhase.MAP -> Unit
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        every { fixture.row.getLong("user_id") } returns 7L
        every { fixture.row.getString("nickname") } returns "원문 닉네임"
        if (phase == FailurePhase.MAP) {
            every { fixture.row.getBoolean("pending") } throws failure andThen false
        } else {
            every { fixture.row.getBoolean("pending") } returns false
        }

        // when
        val unavailable = assertThrows(ContactMemberUnavailableException::class.java) { fixture.query.getAll(listOf(7L)) }

        // then
        assertSame(failure, unavailable.cause)
        assertEquals("Contact member lookup is unavailable.", unavailable.message)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.queries)
        assertEquals(if (phase == FailurePhase.QUERY || phase == FailurePhase.MAP) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null
        assertEquals(listOf(ContactMember(7L, "원문 닉네임", true, false)), fixture.query.getAll(listOf(7L)))
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY, MAP, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val row: ResultSet = mockk<ResultSet>()
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
    }
}
