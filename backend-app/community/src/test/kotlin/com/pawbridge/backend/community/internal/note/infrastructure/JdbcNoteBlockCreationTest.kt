package com.pawbridge.backend.community.internal.note.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.function.Supplier

class JdbcNoteBlockCreationTest {

    @Test
    fun `ID가 역순이고 삽입 행 수가 0일 때__차단을 생성하면__정렬된 회원을 하나씩 잠근 뒤 시각 공급자를 한 번 부르고 원래 시각으로 삽입한다`() {
        // given
        val fixture = Fixture()
        var supplierCalls = 0

        // when
        fixture.creation.create(
            9L,
            3L,
            Supplier {
                assertTrue(fixture.transactions.active)
                assertEquals(4, fixture.jdbc.calls.size)
                supplierCalls++
                fixture.events.add("supplier")
                NOW
            },
        )

        // then
        for (index in 0 until 2) {
            val member = if (index == 0) 3L else 9L
            val insert = fixture.jdbc.calls[index * 2]
            val lock = fixture.jdbc.calls[index * 2 + 1]
            assertEquals(MEMBER_INSERT, insert.sql)
            assertArrayEquals(arrayOf<Any?>(member), insert.arguments)
            assertEquals(LOCK, lock.sql)
            assertArrayEquals(arrayOf<Any?>(member), lock.arguments)
        }
        val block = fixture.jdbc.calls.last()
        assertEquals(BLOCK_INSERT, block.sql)
        assertArrayEquals(arrayOf<Any?>(9L, 3L, Timestamp.from(NOW)), block.arguments)
        assertEquals(NOW, (block.arguments[2] as Timestamp).toInstant())
        assertEquals(
            listOf("begin", "insert:3", "lock:3", "insert:9", "lock:9", "supplier", "block", "commit"),
            fixture.events,
        )
        assertEquals(5, fixture.jdbc.calls.size)
        assertEquals(1, supplierCalls)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `시작 두 번째 잠금 시각 공급자 삽입 또는 커밋이 실패할 때__차단을 생성하면__같은 예외를 전파하고 이후 작업을 멈추며 재시도하지 않는다`(
        phase: FailurePhase,
    ) {
        // given
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("private begin")
            FailurePhase.LOCK, FailurePhase.INSERT -> DataAccessResourceFailureException("private SQL")
            FailurePhase.SUPPLIER -> IllegalStateException("private supplier")
            FailurePhase.COMMIT -> TransactionSystemException("private commit")
        }
        if (phase == FailurePhase.BEGIN) {
            fixture.transactions.beginFailure = failure
        }
        if (phase == FailurePhase.COMMIT) {
            fixture.transactions.commitFailure = failure
        }
        if (phase == FailurePhase.LOCK || phase == FailurePhase.INSERT) {
            fixture.jdbc.failure = failure
            fixture.jdbc.failAtCall = if (phase == FailurePhase.LOCK) 4 else 5
        }
        var supplierCalls = 0

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            fixture.creation.create(
                9L,
                3L,
                Supplier {
                    assertTrue(fixture.transactions.active)
                    supplierCalls++
                    fixture.events.add("supplier")
                    if (phase == FailurePhase.SUPPLIER) {
                        throw failure
                    }
                    NOW
                },
            )
        }

        // then
        assertSame(failure, thrown)
        val prefix = listOf("begin", "insert:3", "lock:3", "insert:9", "lock:9")
        val expected = ArrayList(if (phase == FailurePhase.BEGIN) listOf("begin") else prefix)
        if (phase == FailurePhase.SUPPLIER || phase == FailurePhase.INSERT || phase == FailurePhase.COMMIT) {
            expected.add("supplier")
        }
        if (phase == FailurePhase.INSERT || phase == FailurePhase.COMMIT) {
            expected.add("block")
        }
        if (phase != FailurePhase.BEGIN) {
            expected.add(if (phase == FailurePhase.COMMIT) "commit" else "rollback")
        }
        assertEquals(expected, fixture.events)
        assertEquals(
            if (phase == FailurePhase.BEGIN) {
                0
            } else if (phase == FailurePhase.LOCK || phase == FailurePhase.SUPPLIER) {
                4
            } else {
                5
            },
            fixture.jdbc.calls.size,
        )
        assertEquals(if (phase == FailurePhase.BEGIN || phase == FailurePhase.LOCK) 0 else 1, supplierCalls)
        assertEquals(if (phase == FailurePhase.COMMIT) 1 else 0, fixture.transactions.commits)
        assertEquals(
            if (phase == FailurePhase.LOCK || phase == FailurePhase.SUPPLIER || phase == FailurePhase.INSERT) 1 else 0,
            fixture.transactions.rollbacks,
        )
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, LOCK, SUPPLIER, INSERT, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val creation: JdbcNoteBlockCreation

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.timeout = 15
            creation = JdbcNoteBlockCreation(jdbc, transaction)
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
            val event = if (normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")) {
                "insert:" + arguments[0]
            } else {
                "block"
            }
            record(normalized, arguments, event)
            return 0
        }

        override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg arguments: Any?): T? {
            assertEquals(java.lang.Long::class.java, requiredType)
            record(normalize(sql), arguments, "lock:" + arguments[0])
            return requiredType.cast(arguments[0])
        }

        private fun record(sql: String, arguments: Array<out Any?>, event: String) {
            assertTrue(transactions.active)
            calls.add(Call(sql, arguments.clone()))
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

    private companion object {
        val NOW: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")
        const val MEMBER_INSERT =
            "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING"
        const val LOCK =
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE"
        const val BLOCK_INSERT =
            "INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at) " +
                "VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
    }
}
