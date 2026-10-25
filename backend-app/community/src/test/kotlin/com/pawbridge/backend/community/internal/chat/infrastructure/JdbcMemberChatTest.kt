package com.pawbridge.backend.community.internal.chat.infrastructure

import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Message
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.community.support.anyNonNull
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class JdbcMemberChatTest {

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5])
    fun given__invalid_recipient_body_or_context__when__sending__then__reject_before_transaction_or_member_lookup(
        example: Int,
    ) {
        val f = Fixture()
        val input: Send? = when (example) {
            0 -> null
            1 -> Send(null, REQUEST, "hello", null, null)
            2 -> Send(7L, REQUEST, "hello", null, null)
            3 -> Send(8L, REQUEST, " \n ", null, null)
            4 -> Send(8L, REQUEST, "hello", "POST", null)
            else -> Send(8L, REQUEST, "\uD800", null, null)
        }

        assertThatThrownBy { f.chat.send(7, input) }
            .isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
                assertThat(e.status()).isEqualTo(400)
            }
        assertThat(f.events).isEmpty()
        verifyNoInteractions(f.repository, f.contact, f.users, f.hints)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "REPORT"])
    fun given__active_members_and_owned_context__when__sending__then__preserve_order_cutoff_and_publish_only_after_commit(
        context: String,
    ) {
        val f = Fixture()
        val input = Send(8L, REQUEST, " hello ", context, 19L)
        val normalized = Send(8L, REQUEST, "hello", context, 19L)
        whenever(f.contact.contextAuthor(context, 19L, 8L)).thenReturn(true)

        assertThat(f.chat.send(7, input)).isEqualTo(f.receipt)

        val order = inOrder(f.contact, f.users, f.repository, f.hints)
        order.verify(f.contact).lockMembers(7, 8)
        order.verify(f.users).get(7)
        order.verify(f.users).get(8)
        order.verify(f.repository).request(7, REQUEST, NOW)
        order.verify(f.contact).blocked(7, 8)
        order.verify(f.repository).rate(7, NOW.minusSeconds(60))
        order.verify(f.repository).rate(7, Instant.parse("2029-12-31T15:00:00Z"))
        order.verify(f.contact).contextAuthor(context, 19L, 8L)
        order.verify(f.repository).roomForSend(7, 8)
        order.verify(f.repository).insert(
            7,
            normalized,
            ROOM,
            JdbcMemberChat.hash(normalized),
            NOW,
            Instant.parse("2031-01-01T00:00:00Z"),
        )
        order.verify(f.hints).publish(Signal(7, "SENT", ROOM, 3))
        order.verify(f.hints).publish(Signal(8, "MESSAGE", ROOM, 3))
        assertThat(f.events).containsExactly("begin", "commit", "hint:SENT", "hint:MESSAGE", "cleanup")
    }

    @Test
    fun given__same_request_hash_and_retained_message__when__replaying__then__return_original_receipt_without_insert_or_new_hint() {
        val f = Fixture()
        whenever(f.repository.request(7, REQUEST, NOW)).thenReturn(
            Optional.of(JdbcMemberChatRepository.SavedRequest(JdbcMemberChat.hash(SEND), f.receipt, true)),
        )

        assertThat(f.chat.send(7, SEND)).isEqualTo(f.receipt)

        verify(f.contact).lockMembers(7, 8)
        verify(f.users).get(7)
        verify(f.users).get(8)
        verify(f.repository).request(7, REQUEST, NOW)
        verifyNoMoreInteractions(f.repository)
        verify(f.contact, never()).blocked(anyLong(), anyLong())
        verifyNoInteractions(f.hints)
        assertThat(f.events).containsExactly("begin", "commit", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__request_hash_conflict_or_gone_message__when__replaying__then__preserve_conflict_before_retention_check(
        conflict: Boolean,
    ) {
        val f = Fixture()
        val savedHash = if (conflict) "different" else JdbcMemberChat.hash(SEND)
        whenever(f.repository.request(7, REQUEST, NOW)).thenReturn(
            Optional.of(JdbcMemberChatRepository.SavedRequest(savedHash, f.receipt, false)),
        )

        assertThatThrownBy { f.chat.send(7, SEND) }
            .isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
                assertThat(e.status()).isEqualTo(if (conflict) 409 else 410)
            }
        verify(f.repository, never()).roomForSend(anyLong(), anyLong())
        verifyNoInteractions(f.hints)
        assertThat(f.events).containsExactly("begin", "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(strings = ["block", "minute", "day", "context"])
    fun given__blocked_rate_limited_or_unowned_context__when__sending__then__roll_back_without_insert_or_hint(
        failure: String,
    ) {
        val f = Fixture()
        var input = SEND
        when (failure) {
            "block" -> whenever(f.contact.blocked(7, 8)).thenReturn(true)
            "minute" -> whenever(f.repository.rate(7, NOW.minusSeconds(60))).thenReturn(60L)
            "day" -> whenever(f.repository.rate(7, Instant.parse("2029-12-31T15:00:00Z"))).thenReturn(1000L)
            else -> input = Send(8L, REQUEST, "hello", "POST", 19L)
        }
        val request = input
        val expected = if (failure == "block") 403 else if (failure == "context") 400 else 429

        assertThatThrownBy { f.chat.send(7, request) }
            .isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
                assertThat(e.status()).isEqualTo(expected)
            }
        verify(f.repository, never()).roomForSend(anyLong(), anyLong())
        verifyNoInteractions(f.hints)
        assertThat(f.events).containsExactly("begin", "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__insert_or_commit_failure__when__sending__then__propagate_without_publishing_or_retry(commit: Boolean) {
        val f = Fixture()
        val failure: RuntimeException =
            if (commit) TransactionSystemException("synthetic commit")
            else DataAccessResourceFailureException("synthetic insert")
        if (commit) {
            f.manager.commitFailure = failure
        } else {
            whenever(f.repository.insert(anyLong(), anyNonNull(), any(), anyString(), anyNonNull(), anyNonNull()))
                .thenThrow(failure)
        }

        assertThatThrownBy { f.chat.send(7, SEND) }.isSameAs(failure)

        verify(f.repository).insert(anyLong(), anyNonNull(), any(), anyString(), anyNonNull(), anyNonNull())
        verifyNoInteractions(f.hints)
        assertThat(f.events).containsExactly("begin", if (commit) "commit" else "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(strings = ["login", "inactive", "unavailable"])
    fun given__unusable_current_member__when__checking_active__then__preserve_login_inactive_and_typed_lookup_failures(
        kind: String,
    ) {
        val f = Fixture()
        val id = if (kind == "login") 0L else 7L
        if (kind == "inactive") {
            whenever(f.users.get(7)).thenReturn(ContactMember(7L, "gone", false, true))
        }
        if (kind == "unavailable") {
            whenever(f.users.get(7))
                .thenThrow(ContactMemberUnavailableException(IllegalStateException("private synthetic")))
        }
        val expected = if (kind == "login") 401 else if (kind == "inactive") 403 else 503

        assertThatThrownBy { f.chat.active(id) }
            .isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
                assertThat(e.status()).isEqualTo(expected)
            }
        if (id == 0L) {
            verifyNoInteractions(f.users)
        }
        assertThat(f.events).isEmpty()
        verifyNoInteractions(f.repository, f.contact, f.hints)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__owned_membership__when__reading_or_hiding__then__write_only_owner_and_signal_after_commit(hide: Boolean) {
        val f = Fixture()
        whenever(f.repository.membership(7, ROOM))
            .thenReturn(Optional.of(JdbcMemberChatRepository.Membership(ROOM, 8L, 5, 1, 2, 0)))
        if (hide) {
            f.chat.hide(7, ROOM)
        } else {
            f.chat.read(7, ROOM, 4)
        }

        val order = inOrder(f.contact, f.users, f.repository, f.hints)
        order.verify(f.contact).lockMembers(7, 7)
        order.verify(f.users).get(7)
        order.verify(f.repository).membership(7, ROOM)
        if (hide) {
            order.verify(f.repository).hide(7, ROOM)
            order.verify(f.hints).publish(Signal(7, "HIDDEN", ROOM, 0))
        } else {
            order.verify(f.repository).read(7, ROOM, 4)
            order.verify(f.hints).publish(Signal(7, "READ", ROOM, 4))
            order.verify(f.hints).publish(Signal(8, "READ", ROOM, 4))
        }
        assertThat(f.events[1]).isEqualTo("commit")
        assertThat(f.events[2]).startsWith("hint:")
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 6, 99])
    fun given__invalid_read_sequence_or_missing_membership__when__reading__then__roll_back_without_cross_member_write(
        sequence: Long,
    ) {
        val f = Fixture()
        if (sequence != 99L) {
            whenever(f.repository.membership(7, ROOM))
                .thenReturn(Optional.of(JdbcMemberChatRepository.Membership(ROOM, 8L, 5, 1, 2, 0)))
        }

        assertThatThrownBy { f.chat.read(7, ROOM, sequence) }
            .isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
                assertThat(e.status()).isEqualTo(if (sequence == 99L) 404 else 400)
            }
        verify(f.repository, never()).read(anyLong(), any(), anyLong())
        verifyNoInteractions(f.hints)
        assertThat(f.events).containsExactly("begin", "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__message_page_lookahead__when__reading_before_or_after__then__display_fifty_in_chronological_order_with_correct_cursor(
        after: Boolean,
    ) {
        val f = Fixture()
        whenever(f.repository.membership(7, ROOM))
            .thenReturn(Optional.of(JdbcMemberChatRepository.Membership(ROOM, 8L, 90, 1, 2, 0)))
        val messages = (1L..51L)
            .map { i -> if (after) i else 52 - i }
            .map { i -> Message(ROOM, i, 8L, "body", null, NOW) }
        val before: Long? = if (after) null else 90L
        val following: Long? = if (after) 0L else null
        whenever(f.repository.messages(ROOM, before, following, NOW)).thenReturn(messages)

        val page = f.chat.messages(7, ROOM, before, following)

        assertThat(page.content).hasSize(50)
        assertThat(page.content.first().sequence).isEqualTo(if (after) 1 else 2)
        assertThat(page.content.last().sequence).isEqualTo(if (after) 50 else 51)
        assertThat(page.nextCursor).isEqualTo(if (after) 50L else 2L)
        assertThat(page.counterpartReadThrough).isEqualTo(2)
        assertThat(f.events).isEmpty()
        verifyNoInteractions(f.hints)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__installed_or_absent_chat_tables__when__retaining__then__expire_in_one_transaction_only_when_installed(
        installed: Boolean,
    ) {
        val f = Fixture()
        whenever(f.repository.installed()).thenReturn(installed)

        f.chat.expire()

        if (installed) {
            verify(f.repository).expire(NOW)
            assertThat(f.events).containsExactly("begin", "commit", "cleanup")
        } else {
            verify(f.repository, never()).expire(anyNonNull())
            assertThat(f.events).isEmpty()
        }
        verifyNoInteractions(f.contact, f.users, f.hints)
    }

    private class Fixture {
        val events = ArrayList<String>()
        val repository: JdbcMemberChatRepository = mock(JdbcMemberChatRepository::class.java)
        val contact: NoteSendStore = mock(NoteSendStore::class.java)
        val users: ContactMemberQuery = mock(ContactMemberQuery::class.java)
        val hints: MemberChatHints = mock(MemberChatHints::class.java)
        val manager = Manager(events)
        val receipt = Receipt(REQUEST, ROOM, 3)
        val chat: JdbcMemberChat

        init {
            whenever(users.get(7)).thenReturn(ContactMember(7L, "caller", true, false))
            whenever(users.get(8)).thenReturn(ContactMember(8L, "peer", true, false))
            whenever(repository.request(7, REQUEST, NOW)).thenReturn(Optional.empty())
            whenever(repository.roomForSend(7, 8)).thenReturn(ROOM)
            whenever(repository.insert(anyLong(), anyNonNull(), any(), anyString(), anyNonNull(), anyNonNull()))
                .thenReturn(receipt)
            doAnswer { call ->
                assertThat(events).contains("commit")
                events.add("hint:" + call.getArgument<Signal>(0).kind)
                null
            }.`when`(hints).publish(anyNonNull())
            chat = JdbcMemberChat(
                repository,
                contact,
                users,
                hints,
                Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionTemplate(manager),
            )
        }
    }

    private class Manager(val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var commitFailure: RuntimeException? = null

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            if (commitFailure != null) {
                throw commitFailure!!
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }

        override fun doCleanupAfterCompletion(transaction: Any) {
            events.add("cleanup")
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val REQUEST: UUID = UUID.fromString("9c000000-0000-0000-0000-000000000001")
        val ROOM: UUID = UUID.fromString("9c000000-0000-0000-0000-000000000002")
        val SEND = Send(8L, REQUEST, "hello", null, null)
    }
}
