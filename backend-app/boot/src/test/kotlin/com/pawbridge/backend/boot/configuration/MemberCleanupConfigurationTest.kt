package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.LongConsumer
import java.util.function.Supplier

class MemberCleanupConfigurationTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val manager = mockk<JdbcTransactionManager>()
    private val members = mockk<ContactMemberQuery>()
    private val streams = mockk<PrivateNoteStreamRegistry>()

    init {
        // Spring hashes the beans it registers; that is not an interaction with the collaborator.
        excludeRecords {
            jdbc.hashCode()
            manager.hashCode()
            members.hashCode()
            streams.hashCode()
        }
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
    fun `채팅이 꺼졌거나 지정되지 않았을 때__조립하면__두 트랜잭션이 기록 연결과 레지스트리를 재사용하고 콜백이 순서대로 실행된다`(
        flag: String,
    ) {
        // given
        var context = runner()
        if (flag == "false") {
            context = context.withPropertyValues("pawbridge.chat.enabled=false")
        }
        if (flag == "canonical-false-over-alias-true") {
            context = context.withPropertyValues("pawbridge.chat.enabled=false", "MEMBER_CHAT_ENABLED=true")
        }

        justRun { jdbc.afterPropertiesSet() }
        justRun { manager.afterPropertiesSet() }
        justRun { streams.closeMember(any()) }
        justRun { streams.resyncAll() }
        every { streams.isAutoStartup } returns false
        every { streams.isRunning } returns false
        every { streams.phase } returns 0

        // when
        context.run { application ->
            // then
            assertThat(application).hasNotFailed()
                .hasSingleBean(ContactMemberCleanup::class.java)
                .hasSingleBean(ContactMemberCleanupStore::class.java)
                .hasSingleBean(UserDeletion::class.java)
            val cleanup = application.getBean(ContactMemberCleanup::class.java)
            val store = application.getBean(ContactMemberCleanupStore::class.java)
            val deletion = application.getBean(UserDeletion::class.java)
            assertThat(ReflectionTestUtils.getField(cleanup, "members")).isSameAs(members)
            assertThat(ReflectionTestUtils.getField(cleanup, "store")).isSameAs(store)
            assertThat(ReflectionTestUtils.getField(store, "jdbc")).isSameAs(jdbc)
            assertThat(ReflectionTestUtils.getField(deletion, "jdbc")).isSameAs(jdbc)
            val communityTx = ReflectionTestUtils.getField(store, "transaction") as TransactionTemplate
            val userTx = ReflectionTestUtils.getField(deletion, "transactions") as TransactionTemplate
            assertThat(communityTx).isNotSameAs(userTx)
            for (transaction in listOf(communityTx, userTx)) {
                assertThat(transaction.transactionManager).isSameAs(manager)
                assertThat(transaction.timeout).isEqualTo(15)
                assertThat(transaction.isReadOnly).isFalse()
            }
            // Only the injected callback is exercised here; this is not an actual commit or SSE test.
            clearMocks(streams, answers = false)
            (ReflectionTestUtils.getField(store, "committedCleanup") as LongConsumer).accept(8)
            verifySequence {
                streams.closeMember(8)
                streams.resyncAll()
            }
            confirmVerified(members)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["pawbridge.chat.enabled=true", "MEMBER_CHAT_ENABLED=true"])
    fun `Redis 탈퇴 연결 없이 채팅을 켰을 때__조립하면__정리 트랜잭션 전에 실패한다`(
        property: String,
    ) {
        // given
        val runner = runner().withPropertyValues(property)

        justRun { jdbc.afterPropertiesSet() }
        justRun { manager.afterPropertiesSet() }
        every { streams.isAutoStartup } returns false
        every { streams.isRunning } returns false
        every { streams.phase } returns 0

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Member cleanup with chat enabled requires verified Redis withdrawal wiring")
            verify(exactly = 0) { manager.getTransaction(any()) }
            confirmVerified(members)
        }
    }

    @Test
    fun `채팅이 연결되었을 때__커밋된 정리 콜백이 실행되면__Redis 힌트가 실패해도 탈퇴를 먼저 게시하고 노트를 닫는다`() {
        // given
        val hints = mockk<MemberChatHints>()

        excludeRecords { hints.hashCode() }
        val runner = runner().withBean(MemberChatHints::class.java, Supplier { hints })
            .withPropertyValues("pawbridge.chat.enabled=true")

        justRun { jdbc.afterPropertiesSet() }
        justRun { manager.afterPropertiesSet() }
        justRun { hints.publish(any()) }
        justRun { streams.closeMember(any()) }
        justRun { streams.resyncAll() }
        every { streams.isAutoStartup } returns false
        every { streams.isRunning } returns false
        every { streams.phase } returns 0

        // when
        runner
            .run { context ->
                assertThat(context).hasNotFailed()
                val store = context.getBean(ContactMemberCleanupStore::class.java)
                val callback = ReflectionTestUtils.getField(store, "committedCleanup") as LongConsumer
                // Exclude context initialization calls from this injected callback contract.
                clearMocks(streams, jdbc, manager, members, answers = false)
                callback.accept(8)

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
                callback.accept(8)

                // then
                verifySequence {
                    hints.publish(Signal(8, "WITHDRAWN", null, 0))
                    streams.closeMember(8)
                    streams.resyncAll()
                }
                confirmVerified(jdbc, manager, members)
            }
    }

    @Test
    fun `공개 커뮤니티 정리가 주어졌을 때__회원 삭제를 연결하면__모듈 순환이나 바깥 트랜잭션 없이 그 API만 쓴다`() {
        // given
        val cleanup = mockk<ContactMemberCleanup>()

        justRun { cleanup.remove(any()) }

        // when
        val deletion = MemberCleanupConfiguration(false).userDeletion(jdbc, manager, cleanup, sessions(null))
        (ReflectionTestUtils.getField(deletion, "communityCleanup") as LongConsumer).accept(Long.MAX_VALUE)

        // then
        verify(exactly = 1) { cleanup.remove(Long.MAX_VALUE) }
        confirmVerified(cleanup, jdbc, manager)
        assertThat(ReflectionTestUtils.getField(deletion, "sessions")).isSameAs(UserSessionRevoker.NONE)
    }

    @Test
    fun `인증 세션이 켜졌을 때__회원 삭제를 연결하면__세션 저장소를 통해 세션을 끊는다`() {
        // given
        val revoker = UserSessionRevoker { }
        val cleanup = mockk<ContactMemberCleanup>()

        // when
        val deletion = MemberCleanupConfiguration(false)
            .userDeletion(jdbc, manager, cleanup, sessions(revoker))

        // then
        assertThat(ReflectionTestUtils.getField(deletion, "sessions")).isSameAs(revoker)
    }

    private fun sessions(revoker: UserSessionRevoker?): ObjectProvider<UserSessionRevoker> {
        val factory = DefaultListableBeanFactory()
        if (revoker != null) {
            factory.registerSingleton("sessionRevoker", revoker)
        }
        return factory.getBeanProvider(UserSessionRevoker::class.java)
    }
}
