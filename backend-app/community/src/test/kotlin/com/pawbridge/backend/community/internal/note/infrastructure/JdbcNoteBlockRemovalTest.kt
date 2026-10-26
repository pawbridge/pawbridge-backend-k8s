package com.pawbridge.backend.community.internal.note.infrastructure

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
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.stream.Stream

class JdbcNoteBlockRemovalTest {

    @ParameterizedTest
    @MethodSource("memberPairs")
    fun `ID가 역순이거나 같고 나가는 행이 없을 때__차단을 해제하면__정렬된 서로 다른 회원을 삽입하고 잠근 뒤 가드를 한 번 부르고 0행 삭제를 한다`(
        current: Long,
        target: Long,
        expectedMembers: List<Long>,
    ) {
        // given
        val fixture = Fixture()
        var guardCalls = 0

        // when
        fixture.removal.remove(current, target) {
            assertTrue(fixture.transactions.active)
            assertEquals(expectedMembers.size * 2, fixture.jdbc.calls.size)
            guardCalls++
            fixture.events.add("guard")
        }

        // then
        val expectedEvents = ArrayList<String>()
        expectedEvents.add("begin")
        for (index in expectedMembers.indices) {
            val member = expectedMembers[index]
            val insert = fixture.jdbc.calls[index * 2]
            val lock = fixture.jdbc.calls[index * 2 + 1]
            assertEquals(INSERT, insert.sql)
            assertArrayEquals(arrayOf<Any?>(member), insert.arguments)
            assertEquals(LOCK, lock.sql)
            assertArrayEquals(arrayOf<Any?>(member), lock.arguments)
            expectedEvents.add("insert:$member")
            expectedEvents.add("lock:$member")
        }
        val delete = fixture.jdbc.calls.last()
        assertEquals(DELETE, delete.sql)
        assertArrayEquals(arrayOf<Any?>(current, target), delete.arguments)
        expectedEvents.addAll(listOf("guard", "delete", "commit"))
        assertEquals(expectedEvents, fixture.events)
        assertEquals(expectedMembers.size * 2 + 1, fixture.jdbc.calls.size)
        assertEquals(1, guardCalls)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `시작 두 번째 잠금 가드 삭제 또는 커밋이 실패할 때__차단을 해제하면__같은 예외를 전파하고 이후 작업을 멈추며 재시도하지 않는다`(
        phase: FailurePhase,
    ) {
        // given
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("private begin")
            FailurePhase.LOCK, FailurePhase.DELETE -> DataAccessResourceFailureException("private SQL")
            FailurePhase.GUARD -> IllegalStateException("private guard")
            FailurePhase.COMMIT -> TransactionSystemException("private commit")
        }
        if (phase == FailurePhase.BEGIN) {
            fixture.transactions.beginFailure = failure
        }
        if (phase == FailurePhase.COMMIT) {
            fixture.transactions.commitFailure = failure
        }
        if (phase == FailurePhase.LOCK || phase == FailurePhase.DELETE) {
            fixture.jdbc.failure = failure
            fixture.jdbc.failAtCall = if (phase == FailurePhase.LOCK) 4 else 5
        }
        var guardCalls = 0

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            fixture.removal.remove(9L, 3L) {
                assertTrue(fixture.transactions.active)
                guardCalls++
                fixture.events.add("guard")
                if (phase == FailurePhase.GUARD) {
                    throw failure
                }
            }
        }

        // then
        assertSame(failure, thrown)
        val prefix = listOf("begin", "insert:3", "lock:3", "insert:9", "lock:9")
        val expected = ArrayList(if (phase == FailurePhase.BEGIN) listOf("begin") else prefix)
        if (phase == FailurePhase.GUARD || phase == FailurePhase.DELETE || phase == FailurePhase.COMMIT) {
            expected.add("guard")
        }
        if (phase == FailurePhase.DELETE || phase == FailurePhase.COMMIT) {
            expected.add("delete")
        }
        if (phase != FailurePhase.BEGIN) {
            expected.add(if (phase == FailurePhase.COMMIT) "commit" else "rollback")
        }
        assertEquals(expected, fixture.events)
        assertEquals(
            if (phase == FailurePhase.BEGIN) {
                0
            } else if (phase == FailurePhase.LOCK || phase == FailurePhase.GUARD) {
                4
            } else {
                5
            },
            fixture.jdbc.calls.size,
        )
        assertEquals(if (phase == FailurePhase.BEGIN || phase == FailurePhase.LOCK) 0 else 1, guardCalls)
        assertEquals(if (phase == FailurePhase.COMMIT) 1 else 0, fixture.transactions.commits)
        assertEquals(
            if (phase == FailurePhase.LOCK || phase == FailurePhase.GUARD || phase == FailurePhase.DELETE) 1 else 0,
            fixture.transactions.rollbacks,
        )
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, LOCK, GUARD, DELETE, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val removal: JdbcNoteBlockRemoval

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.timeout = 15
            removal = JdbcNoteBlockRemoval(jdbc, transaction)
        }
    }

    private class RecordingJdbc(
        private val transactions: RecordingTransactionManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var failAtCall = 0
        var failure: RuntimeException? = null

        override fun update(sql: String, vararg arguments: Any?): Int {
            val normalized = normalize(sql)
            record(normalized, arguments, if (normalized.startsWith("INSERT")) "insert:" + arguments[0] else "delete")
            return 0
        }

        override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg arguments: Any?): T? {
            assertEquals(java.lang.Long::class.java, requiredType)
            record(normalize(sql), arguments, "lock:" + arguments[0])
            return requiredType.cast(arguments[0])
        }

        private fun record(sql: String, arguments: Array<out Any?>, event: String) {
            assertTrue(transactions.active)
            calls.add(Call(sql, arguments))
            events.add(event)
            if (calls.size == failAtCall) {
                throw failure!!
            }
        }

        private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    }

    private class RecordingTransactionManager(private val events: MutableList<String>) : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertEquals(15, definition!!.timeout)
            assertFalse(definition.isReadOnly)
            assertFalse(active)
            events.add("begin")
            if (beginFailure != null) {
                throw beginFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            events.add("commit")
            if (commitFailure != null) {
                throw commitFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
            events.add("rollback")
        }
    }

    companion object {
        private const val INSERT =
            "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING"
        private const val LOCK =
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE"
        private const val DELETE =
            "DELETE FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?"

        @JvmStatic
        fun memberPairs(): Stream<Arguments> =
            Stream.of(
                Arguments.of(9L, 3L, listOf(3L, 9L)),
                Arguments.of(7L, 7L, listOf(7L)),
            )
    }
}
