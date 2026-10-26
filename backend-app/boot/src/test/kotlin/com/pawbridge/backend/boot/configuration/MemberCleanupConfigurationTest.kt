package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionStatus
import java.util.function.Supplier
import javax.sql.DataSource

class MemberCleanupConfigurationTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val members = mockk<ContactMemberQuery>()
    private val streams = mockk<PrivateNoteStreamRegistry>()
    private val manager = CommitOnlyTransactionManager(mockk<DataSource>())

    init {
        // Spring hashes the beans it registers; that is not an interaction with the collaborator.
        excludeRecords {
            jdbc.hashCode()
            members.hashCode()
            streams.hashCode()
        }
        justRun { jdbc.afterPropertiesSet() }
        every { jdbc.update(any<String>(), *anyVararg<Any>()) } returns 1
        every { jdbc.queryForObject(any<String>(), any<Class<Any>>(), *anyVararg<Any>()) } returns 1L
        every { jdbc.queryForObject(any<String>(), any<Class<Any>>()) } returns false
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg<Any>()) } returns emptyList()
        every { members.get(8) } returns ContactMember(8L, "synthetic", active = false, deletionPending = true)
        justRun { streams.closeMember(any()) }
        justRun { streams.resyncAll() }
        justRun { streams.destroy() }
        every { streams.isAutoStartup } returns false
        every { streams.isRunning } returns false
        every { streams.phase } returns 0
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(MemberCleanupConfiguration::class.java)
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean(ContactMemberQuery::class.java, Supplier { members })
            .withBean(PrivateNoteStreamRegistry::class.java, Supplier { streams })
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "canonical-false-over-alias-true"])
    fun `채팅이 꺼졌거나 지정되지 않았을 때__커밋된 정리가 끝나면__노트 스트림을 닫고 다시 맞춘다`(flag: String) {
        // given
        var context = runner()
        if (flag == "false") {
            context = context.withPropertyValues("pawbridge.chat.enabled=false")
        }
        if (flag == "canonical-false-over-alias-true") {
            context = context.withPropertyValues("pawbridge.chat.enabled=false", "MEMBER_CHAT_ENABLED=true")
        }

        // when
        context.run { application ->
            assertThat(application).hasNotFailed()
            clearMocks(streams, answers = false)
            application.getBean(ContactMemberCleanup::class.java).remove(8)

            // then
            verifySequence {
                streams.closeMember(8)
                streams.resyncAll()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["pawbridge.chat.enabled=true", "MEMBER_CHAT_ENABLED=true"])
    fun `Redis 탈퇴 연결 없이 채팅을 켰을 때__조립하면__정리 트랜잭션 전에 실패한다`(property: String) {
        // given
        val runner = runner().withPropertyValues(property)

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Member cleanup with chat enabled requires verified Redis withdrawal wiring")
            verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg<Any>()) }
        }
    }

    @Test
    fun `채팅이 연결되었을 때__커밋된 정리가 끝나면__Redis 힌트가 실패해도 탈퇴를 먼저 게시하고 노트를 닫는다`() {
        // given
        val hints = mockk<MemberChatHints>()

        excludeRecords { hints.hashCode() }
        justRun { hints.publish(any()) }
        val runner = runner().withBean(MemberChatHints::class.java, Supplier { hints })
            .withPropertyValues("pawbridge.chat.enabled=true")

        // when
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val cleanup = context.getBean(ContactMemberCleanup::class.java)
            clearMocks(streams, answers = false)
            cleanup.remove(8)

            // then
            verifySequence {
                hints.publish(Signal(8, "WITHDRAWN", null, 0))
                streams.closeMember(8)
                streams.resyncAll()
            }

            // given (the Redis hint now fails)
            clearMocks(hints, streams, answers = false)
            every { hints.publish(any()) } throws IllegalStateException("synthetic private transport failure")

            // when
            cleanup.remove(8)

            // then
            verifySequence {
                hints.publish(Signal(8, "WITHDRAWN", null, 0))
                streams.closeMember(8)
                streams.resyncAll()
            }
        }
    }

    /** Runs the real transaction callbacks (after-commit hooks) without a database connection. */
    private class CommitOnlyTransactionManager(source: DataSource) : JdbcTransactionManager(source) {
        override fun doGetTransaction(): Any = Any()

        override fun isExistingTransaction(transaction: Any): Boolean = false

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        }

        override fun doCommit(status: DefaultTransactionStatus) {
        }

        override fun doRollback(status: DefaultTransactionStatus) {
        }

        override fun doCleanupAfterCompletion(transaction: Any) {
        }
    }
}
