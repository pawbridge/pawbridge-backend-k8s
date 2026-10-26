package com.pawbridge.backend.community.internal.chat.infrastructure

import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Message
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `받는 사람 본문 또는 맥락이 올바르지 않을 때__전송하면__트랜잭션이나 회원 조회 전에 거부한다`(
        example: Int,
    ) {
        // given
        val f = Fixture()
        val input: Send? = when (example) {
            0 -> null
            1 -> Send(null, REQUEST, "hello", null, null)
            2 -> Send(7L, REQUEST, "hello", null, null)
            3 -> Send(8L, REQUEST, " \n ", null, null)
            4 -> Send(8L, REQUEST, "hello", "POST", null)
            else -> Send(8L, REQUEST, "\uD800", null, null)
        }

        // when
        val thrown = assertThatThrownBy { f.chat.send(7, input) }

        // then
        thrown.isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
            assertThat(e.status()).isEqualTo(400)
        }
        assertThat(f.events).isEmpty()
        confirmVerified(f.repository, f.contact, f.users, f.hints)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "REPORT"])
    fun `활성 회원과 본인 소유 맥락이 있을 때__전송하면__순서와 기준 시각을 지키고 커밋 뒤에만 신호를 발행한다`(
        context: String,
    ) {
        // given
        val f = Fixture()
        val input = Send(8L, REQUEST, " hello ", context, 19L)
        val normalized = Send(8L, REQUEST, "hello", context, 19L)

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.users.get(8) } returns ContactMember(8L, "peer", true, false)
        every { f.repository.request(any(), any(), any()) } returns Optional.empty()
        every { f.contact.blocked(any(), any()) } returns false
        every { f.repository.rate(any(), any()) } returns 0L
        every { f.contact.contextAuthor(any(), any(), any()) } returns true
        every { f.repository.roomForSend(any(), any()) } returns ROOM
        every { f.repository.insert(any(), any(), any(), any(), any(), any()) } returns f.receipt
        every { f.hints.publish(any()) } answers {
            assertThat(f.events).contains("commit")
            f.events.add("hint:" + firstArg<Signal>().kind)
            Unit
        }

        // when
        val result = f.chat.send(7, input)

        // then
        assertThat(result).isEqualTo(f.receipt)
        verifyOrder {
            f.contact.lockMembers(7, 8)
            f.users.get(7)
            f.users.get(8)
            f.repository.request(7, REQUEST, NOW)
            f.contact.blocked(7, 8)
            f.repository.rate(7, NOW.minusSeconds(60))
            f.repository.rate(7, Instant.parse("2029-12-31T15:00:00Z"))
            f.contact.contextAuthor(context, 19L, 8L)
            f.repository.roomForSend(7, 8)
            f.repository.insert(
                7,
                normalized,
                ROOM,
                JdbcMemberChat.hash(normalized),
                NOW,
                Instant.parse("2031-01-01T00:00:00Z"),
            )
            f.hints.publish(Signal(7, "SENT", ROOM, 3))
            f.hints.publish(Signal(8, "MESSAGE", ROOM, 3))
        }
        assertThat(f.events).containsExactly("begin", "commit", "hint:SENT", "hint:MESSAGE", "cleanup")
    }

    @Test
    fun `같은 요청 해시와 보관 중인 메시지가 있을 때__재전송하면__삽입이나 새 신호 없이 원래 영수증을 돌려준다`() {
        // given
        val f = Fixture()

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.users.get(8) } returns ContactMember(8L, "peer", true, false)
        every { f.repository.request(any(), any(), any()) } returns Optional.of(
            JdbcMemberChatRepository.SavedRequest(JdbcMemberChat.hash(SEND), f.receipt, true),
        )

        // when
        val result = f.chat.send(7, SEND)

        // then
        assertThat(result).isEqualTo(f.receipt)
        verify(exactly = 1) { f.contact.lockMembers(7, 8) }
        verify(exactly = 1) { f.users.get(7) }
        verify(exactly = 1) { f.users.get(8) }
        verify(exactly = 1) { f.repository.request(7, REQUEST, NOW) }
        confirmVerified(f.repository)
        verify(exactly = 0) { f.contact.blocked(any(), any()) }
        confirmVerified(f.hints)
        assertThat(f.events).containsExactly("begin", "commit", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `요청 해시가 충돌하거나 메시지가 사라졌을 때__재전송하면__보관 확인 전에 충돌을 먼저 유지한다`(
        conflict: Boolean,
    ) {
        // given
        val f = Fixture()
        val savedHash = if (conflict) "different" else JdbcMemberChat.hash(SEND)

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.users.get(8) } returns ContactMember(8L, "peer", true, false)
        every { f.repository.request(any(), any(), any()) } returns Optional.of(
            JdbcMemberChatRepository.SavedRequest(savedHash, f.receipt, false),
        )

        // when
        val thrown = assertThatThrownBy { f.chat.send(7, SEND) }

        // then
        thrown.isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
            assertThat(e.status()).isEqualTo(if (conflict) 409 else 410)
        }
        verify(exactly = 0) { f.repository.roomForSend(any(), any()) }
        confirmVerified(f.hints)
        assertThat(f.events).containsExactly("begin", "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(strings = ["block", "minute", "day", "context"])
    fun `차단되었거나 전송 한도에 걸렸거나 맥락 소유자가 아닐 때__전송하면__삽입이나 신호 없이 롤백한다`(
        failure: String,
    ) {
        // given
        val f = Fixture()
        val input = if (failure == "context") Send(8L, REQUEST, "hello", "POST", 19L) else SEND
        val expected = if (failure == "block") 403 else if (failure == "context") 400 else 429

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.users.get(8) } returns ContactMember(8L, "peer", true, false)
        every { f.repository.request(any(), any(), any()) } returns Optional.empty()
        every { f.contact.blocked(7, 8) } returns (failure == "block")
        every { f.repository.rate(7, NOW.minusSeconds(60)) } returns if (failure == "minute") 60L else 0L
        every { f.repository.rate(7, Instant.parse("2029-12-31T15:00:00Z")) } returns if (failure == "day") 1000L else 0L
        every { f.contact.contextAuthor(any(), any(), any()) } returns false

        // when
        val thrown = assertThatThrownBy { f.chat.send(7, input) }

        // then
        thrown.isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
            assertThat(e.status()).isEqualTo(expected)
        }
        verify(exactly = 0) { f.repository.roomForSend(any(), any()) }
        confirmVerified(f.hints)
        assertThat(f.events).containsExactly("begin", "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `삽입 또는 커밋이 실패할 때__전송하면__발행이나 재시도 없이 실패를 전파한다`(commit: Boolean) {
        // given
        val f = Fixture()
        val failure: RuntimeException =
            if (commit) TransactionSystemException("synthetic commit")
            else DataAccessResourceFailureException("synthetic insert")
        if (commit) {
            f.manager.commitFailure = failure
        }

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.users.get(8) } returns ContactMember(8L, "peer", true, false)
        every { f.repository.request(any(), any(), any()) } returns Optional.empty()
        every { f.contact.blocked(any(), any()) } returns false
        every { f.repository.rate(any(), any()) } returns 0L
        every { f.repository.roomForSend(any(), any()) } returns ROOM
        if (commit) {
            every { f.repository.insert(any(), any(), any(), any(), any(), any()) } returns f.receipt
        } else {
            every { f.repository.insert(any(), any(), any(), any(), any(), any()) } throws failure
        }

        // when
        val thrown = assertThatThrownBy { f.chat.send(7, SEND) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { f.repository.insert(any(), any(), any(), any(), any(), any()) }
        confirmVerified(f.hints)
        assertThat(f.events).containsExactly("begin", if (commit) "commit" else "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(strings = ["login", "inactive", "unavailable"])
    fun `현재 회원을 쓸 수 없을 때__활성 여부를 확인하면__로그인 비활성 조회 실패를 각각 유지한다`(
        kind: String,
    ) {
        // given
        val f = Fixture()
        val id = if (kind == "login") 0L else 7L
        val expected = if (kind == "login") 401 else if (kind == "inactive") 403 else 503
        if (kind == "inactive") {
            every { f.users.get(7) } returns ContactMember(7L, "gone", false, true)
        }
        if (kind == "unavailable") {
            every { f.users.get(7) } throws ContactMemberUnavailableException(IllegalStateException("private synthetic"))
        }

        // when
        val thrown = assertThatThrownBy { f.chat.active(id) }

        // then
        thrown.isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
            assertThat(e.status()).isEqualTo(expected)
        }
        if (id == 0L) {
            confirmVerified(f.users)
        }
        assertThat(f.events).isEmpty()
        confirmVerified(f.repository, f.contact, f.hints)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `본인 소유 멤버십이 있을 때__읽음 처리하거나 숨기면__소유자 쪽만 쓰고 커밋 뒤에 신호를 보낸다`(hide: Boolean) {
        // given
        val f = Fixture()

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.repository.membership(any(), any()) } returns
            Optional.of(JdbcMemberChatRepository.Membership(ROOM, 8L, 5, 1, 2, 0))
        justRun { f.repository.hide(any(), any()) }
        justRun { f.repository.read(any(), any(), any()) }
        every { f.hints.publish(any()) } answers {
            assertThat(f.events).contains("commit")
            f.events.add("hint:" + firstArg<Signal>().kind)
            Unit
        }

        // when
        if (hide) {
            f.chat.hide(7, ROOM)
        } else {
            f.chat.read(7, ROOM, 4)
        }

        // then
        if (hide) {
            verifyOrder {
                f.contact.lockMembers(7, 7)
                f.users.get(7)
                f.repository.membership(7, ROOM)
                f.repository.hide(7, ROOM)
                f.hints.publish(Signal(7, "HIDDEN", ROOM, 0))
            }
        } else {
            verifyOrder {
                f.contact.lockMembers(7, 7)
                f.users.get(7)
                f.repository.membership(7, ROOM)
                f.repository.read(7, ROOM, 4)
                f.hints.publish(Signal(7, "READ", ROOM, 4))
                f.hints.publish(Signal(8, "READ", ROOM, 4))
            }
        }
        assertThat(f.events[1]).isEqualTo("commit")
        assertThat(f.events[2]).startsWith("hint:")
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 6, 99])
    fun `읽음 번호가 잘못되었거나 멤버십이 없을 때__읽음 처리하면__다른 회원에게 쓰지 않고 롤백한다`(
        sequence: Long,
    ) {
        // given
        val f = Fixture()
        val membership = if (sequence != 99L) {
            Optional.of(JdbcMemberChatRepository.Membership(ROOM, 8L, 5, 1, 2, 0))
        } else {
            Optional.empty()
        }

        justRun { f.contact.lockMembers(any(), any()) }
        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.repository.membership(any(), any()) } returns membership

        // when
        val thrown = assertThatThrownBy { f.chat.read(7, ROOM, sequence) }

        // then
        thrown.isInstanceOfSatisfying(MemberChatRejectedException::class.java) { e ->
            assertThat(e.status()).isEqualTo(if (sequence == 99L) 404 else 400)
        }
        verify(exactly = 0) { f.repository.read(any(), any(), any()) }
        confirmVerified(f.hints)
        assertThat(f.events).containsExactly("begin", "rollback", "cleanup")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `메시지 페이지 선읽기가 있을 때__이전 또는 이후를 조회하면__50개를 시간순으로 보이고 커서를 맞게 만든다`(
        after: Boolean,
    ) {
        // given
        val f = Fixture()
        val messages = (1L..51L)
            .map { i -> if (after) i else 52 - i }
            .map { i -> Message(ROOM, i, 8L, "body", null, NOW) }
        val before: Long? = if (after) null else 90L
        val following: Long? = if (after) 0L else null

        every { f.users.get(7) } returns ContactMember(7L, "caller", true, false)
        every { f.repository.membership(any(), any()) } returns
            Optional.of(JdbcMemberChatRepository.Membership(ROOM, 8L, 90, 1, 2, 0))
        every { f.repository.messages(any(), any(), any(), any()) } returns messages

        // when
        val page = f.chat.messages(7, ROOM, before, following)

        // then
        assertThat(page.content).hasSize(50)
        assertThat(page.content.first().sequence).isEqualTo(if (after) 1 else 2)
        assertThat(page.content.last().sequence).isEqualTo(if (after) 50 else 51)
        assertThat(page.nextCursor).isEqualTo(if (after) 50L else 2L)
        assertThat(page.counterpartReadThrough).isEqualTo(2)
        verify(exactly = 1) { f.repository.messages(ROOM, before, following, NOW) }
        assertThat(f.events).isEmpty()
        confirmVerified(f.hints)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `채팅 테이블이 설치되었거나 없을 때__보관 기간을 정리하면__설치된 경우에만 한 트랜잭션으로 만료한다`(
        installed: Boolean,
    ) {
        // given
        val f = Fixture()

        every { f.repository.installed() } returns installed
        justRun { f.repository.expire(any()) }

        // when
        f.chat.expire()

        // then
        if (installed) {
            verify(exactly = 1) { f.repository.expire(NOW) }
            assertThat(f.events).containsExactly("begin", "commit", "cleanup")
        } else {
            verify(exactly = 0) { f.repository.expire(any()) }
            assertThat(f.events).isEmpty()
        }
        confirmVerified(f.contact, f.users, f.hints)
    }

    private class Fixture {
        val events = ArrayList<String>()
        val repository: JdbcMemberChatRepository = mockk<JdbcMemberChatRepository>()
        val contact: NoteSendStore = mockk<NoteSendStore>()
        val users: ContactMemberQuery = mockk<ContactMemberQuery>()
        val hints: MemberChatHints = mockk<MemberChatHints>()
        val manager = Manager(events)
        val receipt = Receipt(REQUEST, ROOM, 3)
        val chat = JdbcMemberChat(
            repository,
            contact,
            users,
            hints,
            Clock.fixed(NOW, ZoneOffset.UTC),
            TransactionTemplate(manager),
        )
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
