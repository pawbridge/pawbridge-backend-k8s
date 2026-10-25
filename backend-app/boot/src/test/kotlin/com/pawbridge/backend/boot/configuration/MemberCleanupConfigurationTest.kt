package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.anyValue
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.LongConsumer
import java.util.function.Supplier

class MemberCleanupConfigurationTest {
    private val jdbc = mock(JdbcTemplate::class.java)
    private val manager = mock(JdbcTransactionManager::class.java)
    private val members = mock(ContactMemberQuery::class.java)
    private val streams = mock(PrivateNoteStreamRegistry::class.java)

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(MemberCleanupConfiguration::class.java)
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean(ContactMemberQuery::class.java, Supplier { members })
            .withBean(PrivateNoteStreamRegistry::class.java, Supplier { streams })
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "canonical-false-over-alias-true"])
    fun given__disabled_or_unspecified_chat__when__assembling__then__reuse_writer_and_registry_for_both_transactions_and_ordered_callbacks(
        flag: String,
    ) {
        var context = runner()
        if (flag == "false") {
            context = context.withPropertyValues("pawbridge.chat.enabled=false")
        }
        if (flag == "canonical-false-over-alias-true") {
            context = context.withPropertyValues("pawbridge.chat.enabled=false", "MEMBER_CHAT_ENABLED=true")
        }
        context.run { application ->
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
            clearInvocations(streams)
            (ReflectionTestUtils.getField(store, "committedCleanup") as LongConsumer).accept(8)
            val sequence = inOrder(streams)
            sequence.verify(streams).closeMember(8)
            sequence.verify(streams).resyncAll()
            verifyNoMoreInteractions(streams)
            verifyNoInteractions(members)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["pawbridge.chat.enabled=true", "MEMBER_CHAT_ENABLED=true"])
    fun given__enabled_chat_without_redis_withdrawal_wiring__when__assembling__then__fail_before_any_cleanup_transaction(
        property: String,
    ) {
        runner().withPropertyValues(property).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Member cleanup with chat enabled requires verified Redis withdrawal wiring")
            verify(manager, never()).getTransaction(anyValue<TransactionDefinition>())
            verifyNoInteractions(members)
        }
    }

    @Test
    fun given__wired_chat__when__committed_cleanup_callback_runs__then__publish_withdrawal_before_note_close_even_if_redis_hint_fails() {
        val hints = mock(MemberChatHints::class.java)
        runner().withBean(MemberChatHints::class.java, Supplier { hints })
            .withPropertyValues("pawbridge.chat.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                val store = context.getBean(ContactMemberCleanupStore::class.java)
                val callback = ReflectionTestUtils.getField(store, "committedCleanup") as LongConsumer
                // Exclude context initialization calls from this injected callback contract.
                clearInvocations(streams, jdbc, manager, members)
                callback.accept(8)
                val ordered = inOrder(hints, streams)
                ordered.verify(hints).publish(Signal(8, "WITHDRAWN", null, 0))
                ordered.verify(streams).closeMember(8)
                ordered.verify(streams).resyncAll()
                verifyNoMoreInteractions(hints, streams)
                clearInvocations(hints, streams)
                doThrow(IllegalStateException("synthetic private transport failure")).`when`(hints)
                    .publish(anyNonNull<Signal>())
                callback.accept(8)
                val failedHintOrder = inOrder(hints, streams)
                failedHintOrder.verify(hints).publish(Signal(8, "WITHDRAWN", null, 0))
                failedHintOrder.verify(streams).closeMember(8)
                failedHintOrder.verify(streams).resyncAll()
                verifyNoInteractions(jdbc, manager, members)
            }
    }

    @Test
    fun given__public_community_cleanup__when__wiring_user_deletion__then__consume_only_that_api_without_a_module_cycle_or_outer_transaction() {
        val cleanup = mock(ContactMemberCleanup::class.java)
        val deletion = MemberCleanupConfiguration(false).userDeletion(jdbc, manager, cleanup, sessions(null))
        (ReflectionTestUtils.getField(deletion, "communityCleanup") as LongConsumer).accept(Long.MAX_VALUE)
        verify(cleanup).remove(Long.MAX_VALUE)
        verifyNoMoreInteractions(cleanup)
        verifyNoInteractions(jdbc, manager)
        assertThat(ReflectionTestUtils.getField(deletion, "sessions")).isSameAs(UserSessionRevoker.NONE)
    }

    @Test
    fun given__auth_sessions_enabled__when__wiring_user_deletion__then__revoke_through_the_session_store() {
        val revoker = UserSessionRevoker { }

        val deletion = MemberCleanupConfiguration(false)
            .userDeletion(jdbc, manager, mock(ContactMemberCleanup::class.java), sessions(revoker))

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
