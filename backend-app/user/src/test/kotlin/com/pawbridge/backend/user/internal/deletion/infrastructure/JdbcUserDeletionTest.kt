package com.pawbridge.backend.user.internal.deletion.infrastructure

import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.util.function.LongConsumer

class JdbcUserDeletionTest {
    @Test
    fun `회원이 있을 때__삭제하면__대기 표시를 먼저 커밋하고 커뮤니티 정리 뒤 두 트랜잭션 밖에서만 계정을 지운다`() {
        // given
        val f = Fixture()

        // when
        f.deletion.delete(7)

        // then
        assertEquals(
            listOf(
                "begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "commit", "cleanup",
            ),
            f.events,
        )
        assertEquals(2, f.manager.commits)
        assertEquals(0, f.manager.rollbacks)
        assertFalse(f.state.user)
        assertFalse(f.state.pending)
        assertFalse(f.state.favorites || f.state.applications)
        f.returned()
    }

    @Test
    fun `인증 세션이 있을 때__삭제를 시작하면__대기 표시 트랜잭션 안에서 모든 세션을 끊는다`() {
        // given
        val f = Fixture()
        val tx = TransactionTemplate(f.manager)
        tx.timeout = 15
        val deletion = JdbcUserDeletion(
            f.jdbc,
            tx,
            LongConsumer { f.events.add("community") },
            UserSessionRevoker { userId ->
                assertTrue(f.manager.active)
                f.events.add("revoke-$userId")
            },
        )

        // when
        deletion.delete(7)

        // then
        assertEquals(
            listOf(
                "begin", "lock", "pending", "revoke-7", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "commit", "cleanup",
            ),
            f.events,
        )
        f.returned()
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 7])
    fun `존재하지 않는 id이거나 양수가 아닌 id일 때__삭제하면__원래 id로 조회하고 커뮤니티 정리와 대기 표시 없이 회원 없음으로 실패한다`(
        id: Long,
    ) {
        // given
        val f = Fixture()
        f.state.user = false

        // when
        assertThrows(UserNotFoundException::class.java) { f.deletion.delete(id) }

        // then
        assertEquals(listOf("begin", "lock", "rollback", "cleanup"), f.events)
        assertEquals(listOf(id), f.jdbc.ids)
        assertFalse(f.state.pending)
        assertEquals(0, f.communityCalls)
        f.returned()
    }

    @Test
    fun `대기 표시 커밋 뒤 커뮤니티 정리가 실패했을 때__승인된 재시도가 성공하면__재활성화 없이 대기 표시를 유지한 채 이어서 삭제한다`() {
        // given
        val f = Fixture()
        f.communityFails = true

        // when
        val failure = assertThrows(MemberCleanupUnavailableException::class.java) { f.deletion.delete(7) }

        // then
        assertEquals("쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.", failure.message)
        assertNull(failure.cause)
        assertEquals(listOf("begin", "lock", "pending", "commit", "cleanup", "community"), f.events)
        assertTrue(f.state.user && f.state.pending && f.state.favorites && f.state.applications)
        assertEquals(1, f.manager.commits)
        f.returned()
        f.communityFails = false
        f.events.clear()
        f.deletion.delete(7)
        assertEquals(2, f.communityCalls)
        assertEquals(3, f.manager.commits)
        assertFalse(f.state.user)
        assertFalse(f.state.pending)
        assertEquals(
            listOf(
                "begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "commit", "cleanup",
            ),
            f.events,
        )
        f.returned()
    }

    @Test
    fun `대기 표시 커밋이 실패했을 때__삭제하면__데이터베이스 롤백을 단정하지 않고 커뮤니티 정리 전에 예외를 전파한다`() {
        // given
        val f = Fixture()
        val failure = TransactionSystemException("private-pending-commit")
        f.manager.commitFailure = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { f.deletion.delete(7) }

        // then
        assertSame(failure, thrown)
        assertEquals(0, f.communityCalls)
        assertEquals(listOf("begin", "lock", "pending", "commit", "cleanup"), f.events)
        // A commit exception alone cannot determine the database's committed state.
        f.returned()
    }

    @ParameterizedTest
    @EnumSource(FinalDelete::class)
    fun `최종 계정 SQL이 실패했을 때__같은 실패가 전파된 뒤 재시도하면__커밋된 대기 표시를 보존하고 두 단계를 이어서 수행한다`(
        phase: FinalDelete,
    ) {
        // given
        val f = Fixture()
        f.jdbc.failedDelete = phase
        val failure = DataAccessResourceFailureException("private-final-sql")
        f.jdbc.failure = failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { f.deletion.delete(7) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, f.manager.commits)
        assertEquals(1, f.manager.rollbacks)
        assertTrue(f.state.user && f.state.pending && f.state.favorites && f.state.applications)
        val deletes = FinalDelete.values().take(phase.ordinal + 1).map { v -> v.table }
        assertEquals(deletes, f.events.subList(7, f.events.size - 2).filter { v -> v != "lookup" })
        assertEquals(phase == FinalDelete.USER, f.events.contains("lookup"))
        f.returned()
        f.jdbc.failedDelete = null
        f.deletion.delete(7)
        assertEquals(2, f.communityCalls)
        assertFalse(f.state.user)
        assertFalse(f.state.pending)
        f.returned()
    }

    @Test
    fun `최종 조회에서 회원이 없을 때__커뮤니티 정리 뒤 마무리하면__최종 삭제 없이 원래의 무동작 성공을 유지한다`() {
        // given
        val f = Fixture()
        f.jdbc.finalLookupMissing = true

        // when
        f.deletion.delete(7)

        // then
        assertEquals(
            listOf(
                "begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "commit", "cleanup",
            ),
            f.events,
        )
        assertEquals(2, f.manager.commits)
        assertEquals(0, f.manager.rollbacks)
        f.returned()
    }

    @Test
    fun `회원은 찾았지만 최종 삭제가 0행일 때__커뮤니티 정리 뒤 마무리하면__최종 트랜잭션을 낡은 상태로 보고 롤백한다`() {
        // given
        val f = Fixture()
        f.jdbc.finalDeleteCount = 0

        // when
        assertThrows(OptimisticLockingFailureException::class.java) { f.deletion.delete(7) }

        // then
        assertEquals(1, f.manager.commits)
        assertEquals(1, f.manager.rollbacks)
        assertTrue(f.state.user && f.state.pending && f.state.favorites && f.state.applications)
        assertEquals(
            listOf(
                "begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "rollback", "cleanup",
            ),
            f.events,
        )
        f.returned()
    }

    enum class FinalDelete(val table: String) {
        FAVORITES("favorites"),
        APPLICATIONS("shelter_applications"),
        USER("users"),
    }

    private class State {
        var user = true
        var pending = false
        var favorites = true
        var applications = true

        fun values(): BooleanArray {
            return booleanArrayOf(user, pending, favorites, applications)
        }

        fun restore(v: BooleanArray) {
            user = v[0]
            pending = v[1]
            favorites = v[2]
            applications = v[3]
        }
    }

    private class Fixture {
        val state = State()
        val events = ArrayList<String>()
        val manager = Manager(state, events)
        val jdbc = RecordingJdbc(this)
        val deletion: JdbcUserDeletion
        var communityCalls = 0
        var communityFails = false

        init {
            val tx = TransactionTemplate(manager)
            tx.timeout = 15
            deletion = JdbcUserDeletion(
                jdbc,
                tx,
                LongConsumer { id ->
                    assertEquals(7L, id)
                    assertFalse(manager.active)
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                    assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
                    assertTrue(state.pending)
                    assertTrue(manager.commits > 0)
                    events.add("community")
                    communityCalls++
                    if (communityFails) {
                        throw IllegalStateException("private-community-body-token")
                    }
                },
            )
        }

        fun returned() {
            assertFalse(manager.active)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        }
    }

    private class RecordingJdbc(private val f: Fixture) : JdbcTemplate() {
        val ids = ArrayList<Long>()
        var failedDelete: FinalDelete? = null
        var failure: RuntimeException? = null
        var finalLookupMissing = false
        var finalDeleteCount = 1

        @Suppress("UNCHECKED_CAST")
        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            assertTrue(f.manager.active)
            val finalLookup = sql == "SELECT user_id FROM pawbridge_user.users WHERE user_id=?"
            if (!finalLookup) {
                assertEquals("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE", sql)
            }
            assertEquals(1, args.size)
            ids.add(args[0] as Long)
            f.events.add(if (finalLookup) "lookup" else "lock")
            if (!f.state.user || finalLookup && finalLookupMissing) {
                return mutableListOf()
            }
            try {
                val row = mockk<ResultSet>()
                every { row.getLong(1) } returns args[0] as Long
                return mutableListOf(rowMapper.mapRow(row, 0) as T)
            } catch (failure: SQLException) {
                throw AssertionError(failure)
            }
        }

        override fun update(sql: String, vararg args: Any?): Int {
            assertTrue(f.manager.active)
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            assertArrayEquals(arrayOf<Any?>(7L), args)
            if (sql.startsWith("INSERT")) {
                assertEquals("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING", sql)
                f.events.add("pending")
                val existed = f.state.pending
                f.state.pending = true
                return if (existed) 0 else 1
            }
            val phase = FinalDelete.values()
                .firstOrNull { v -> sql == "DELETE FROM pawbridge_user." + v.table + " WHERE user_id=?" }
                ?: throw AssertionError("Unexpected deletion SQL")
            f.events.add(phase.table)
            if (phase == failedDelete) {
                throw failure!!
            }
            if (phase == FinalDelete.USER && finalDeleteCount != 1) {
                return finalDeleteCount
            }
            when (phase) {
                FinalDelete.FAVORITES -> f.state.favorites = false
                FinalDelete.APPLICATIONS -> f.state.applications = false
                FinalDelete.USER -> {
                    f.state.user = false
                    f.state.pending = false
                }
            }
            return 1
        }
    }

    /** Recording state and real Spring synchronization only; no PostgreSQL atomicity or FK proof. */
    private class Manager(
        private val state: State,
        private val events: MutableList<String>,
    ) : AbstractPlatformTransactionManager() {
        var active = false
        var before: BooleanArray? = null
        var commits = 0
        var rollbacks = 0
        var commitFailure: RuntimeException? = null

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            assertEquals(15, definition.timeout)
            assertFalse(definition.isReadOnly)
            before = state.values()
            active = true
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            if (commitFailure != null) {
                throw commitFailure!!
            }
            commits++
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks++
            state.restore(before!!)
            events.add("rollback")
        }

        override fun doCleanupAfterCompletion(transaction: Any) {
            active = false
            events.add("cleanup")
        }
    }
}
