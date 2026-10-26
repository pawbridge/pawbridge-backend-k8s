package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.BLOCKED
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.CONFLICT
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.CONTEXT_AUTHOR
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.GONE
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.INACTIVE
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.INVALID_BODY
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.INVALID_CONTEXT
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.INVALID_REQUEST
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.LOGIN
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.NOT_FOUND
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.RATE_LIMIT
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.REPLY_MISMATCH
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import java.util.stream.Stream

class SendPrivateNoteTest {

    @ParameterizedTest
    @MethodSource("invalidInputs")
    fun `잘못된 ID와 요청과 본문 또는 맥락이 주어졌을 때__보내면__트랜잭션과 회원 조회와 시각 읽기와 UUID 생성 전에 거절한다`(
        current: Long,
        request: SendNote?,
        reason: NoteSendRejectedException.Reason,
    ) {
        // given
        val f = Fixture()

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) { f.app.send(current, request) }

        // then
        assertEquals(reason, failure.reason())
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.generated)
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @MethodSource("validBodies")
    fun `유효한 본문과 활성 회원이 주어졌을 때__보내면__원문 텍스트와 해시와 정책 순서와 서울 기준 하루와 UTC 기준 1년을 보존한다`(
        raw: String,
        stripped: String,
        hash: String,
    ) {
        // given
        val f = Fixture()
        f.minuteCount = 9
        f.dayCount = 99

        // when
        val receipt = f.app.send(7L, request(raw))

        // then
        assertEquals(Receipt(NOTE), receipt)
        assertEquals(
            listOf(
                "begin", "lock:7:8", "member:7", "member:8", "previous", "blocked", "clock:1",
                "rate:1", "rate:2", "uuid", "insert", "commit",
            ),
            f.events,
        )
        assertEquals(listOf(NOW.minusSeconds(60), Instant.parse("2028-02-29T15:00:00Z")), f.rateTimes)
        assertEquals(stripped, f.inserted!!.body)
        assertEquals(hash, f.inserted!!.requestHash)
        assertEquals(NOTE, f.inserted!!.noteId)
        assertEquals(REQUEST, f.inserted!!.requestId)
        assertEquals(7L, f.inserted!!.senderId)
        assertEquals(8L, f.inserted!!.recipientId)
        assertNull(f.inserted!!.replyTo)
        assertNull(f.inserted!!.contextType)
        assertNull(f.inserted!!.contextId)
        assertEquals(NOW, f.inserted!!.createdAt)
        assertEquals(Instant.parse("2029-02-28T15:00:00.123456789Z"), f.inserted!!.expiresAt)
        assertEquals(Notification(NOTE, "PRIVATE_NOTE", 7L, " raw \n", NOW, false, "/notes/$NOTE"), f.notification)
        assertEquals(1, f.generated)
        verify(exactly = 1) { f.clock.instant() }
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        confirmVerified(f.members, f.clock)
        assertFalse(f.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["hello\n😀", " \thello\n😀\n "])
    fun `같은 공백 제거 요청이 이미 저장되어 있을 때__다시 보내면__회원을 확인하고 차단과 시각과 새 작업 전에 원래 ID를 돌려준다`(
        body: String,
    ) {
        // given
        val f = Fixture()
        f.previous = Optional.of(NoteSendStore.PreviousRequest(PARENT, HASH))
        f.blocked = true

        // when
        val receipt = f.app.send(7L, request(body))

        // then
        assertEquals(Receipt(PARENT), receipt)
        assertEquals(listOf("begin", "lock:7:8", "member:7", "member:8", "previous", "commit"), f.events)
        assertEquals(listOf<Any?>(7L, REQUEST), f.previousArguments)
        assertNull(f.inserted)
        assertEquals(0, f.generated)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @MethodSource("previousRequests")
    fun `사라졌거나 충돌하는 요청이 주어졌을 때__보내면__해시 충돌보다 사라짐을 먼저 판단하고 시각 읽기 전에 멈춘다`(
        previous: NoteSendStore.PreviousRequest,
        reason: NoteSendRejectedException.Reason,
    ) {
        // given
        val f = Fixture()
        f.previous = Optional.of(previous)

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request("hello\n😀")) }

        // then
        assertEquals(reason, failure.reason())
        assertEquals(listOf("begin", "lock:7:8", "member:7", "member:8", "previous", "rollback"), f.events)
        assertNull(f.inserted)
        assertEquals(0, f.generated)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @ValueSource(longs = [7L, 8L])
    fun `현재 회원 또는 수신자가 비활성일 때__보내면__이전 요청 조회 전에 회원 조회 순서대로 거절한다`(
        inactive: Long,
    ) {
        // given
        val f = Fixture()
        f.inactive.add(inactive)
        f.previous = Optional.of(NoteSendStore.PreviousRequest(null, null))

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request("body")) }

        // then
        assertEquals(INACTIVE, failure.reason())
        val expected = ArrayList(listOf("begin", "lock:7:8", "member:7"))
        if (inactive == 8L) {
            expected.add("member:8")
        }
        expected.add("rollback")
        assertEquals(expected, f.events)
        assertEquals(0, f.generated)
        verify(exactly = 1) { f.members.get(7L) }
        if (inactive == 8L) {
            verify(exactly = 1) { f.members.get(8L) }
        }
        confirmVerified(f.members, f.clock)
    }

    @Test
    fun `수신자 회원 조회를 쓸 수 없을 때__보내면__요청 조회나 시각 읽기 전에 같은 타입 있는 실패를 전파한다`() {
        // given
        val f = Fixture()
        val failure = ContactMemberUnavailableException(IllegalStateException("private-user-detail"))
        f.unavailable = failure

        // when
        val thrown = assertThrows(ContactMemberUnavailableException::class.java) { f.app.send(7L, request("body")) }

        // then
        assertSame(failure, thrown)
        assertEquals(listOf("begin", "lock:7:8", "member:7", "member:8", "rollback"), f.events)
        assertEquals(0, f.generated)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        confirmVerified(f.members, f.clock)
    }

    @Test
    fun `새 요청이 차단되었을 때__보내면__시각을 읽거나 한도를 조회하기 전에 멈춘다`() {
        // given
        val f = Fixture()
        f.blocked = true

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request("body")) }

        // then
        assertEquals(BLOCKED, failure.reason())
        assertEquals(
            listOf("begin", "lock:7:8", "member:7", "member:8", "previous", "blocked", "rollback"),
            f.events,
        )
        assertEquals(0, f.generated)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @ValueSource(strings = ["minute", "day"])
    fun `분 또는 일 한도에 도달했을 때__보내면__답장 맥락과 UUID 전에 경계 포함 조회를 단락시켜 거절한다`(
        limit: String,
    ) {
        // given
        val f = Fixture()
        f.minuteCount = if (limit == "minute") 10 else 9
        f.dayCount = 100
        val request = SendNote(8L, "body", REQUEST, PARENT, "POST", 3L)

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request) }

        // then
        assertEquals(RATE_LIMIT, failure.reason())
        assertEquals(
            if (limit == "minute") {
                listOf(NOW.minusSeconds(60))
            } else {
                listOf(NOW.minusSeconds(60), Instant.parse("2028-02-29T15:00:00Z"))
            },
            f.rateTimes,
        )
        assertFalse(f.events.contains("reply"))
        assertFalse(f.events.contains("context:POST"))
        assertNull(f.inserted)
        assertEquals(0, f.generated)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        verify(exactly = 1) { f.clock.instant() }
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @MethodSource("rejectedReplies")
    fun `답장 쪽지가 없거나 맞지 않을 때__보내면__답장 시각을 잡고 맥락과 UUID 전에 거절한다`(
        parent: Optional<NoteSendStore.Reply>,
        reason: NoteSendRejectedException.Reason,
    ) {
        // given
        val f = Fixture()
        f.parent = parent

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) {
            f.app.send(7L, SendNote(8L, "body", REQUEST, PARENT, "POST", 3L))
        }

        // then
        assertEquals(reason, failure.reason())
        assertEquals(listOf<Any?>(7L, PARENT, NOW.plusSeconds(9)), f.replyArguments)
        assertFalse(f.events.contains("context:POST"))
        assertEquals(0, f.generated)
        assertNull(f.inserted)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        verify(exactly = 2) { f.clock.instant() }
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "REPORT"])
    fun `맥락이 수신자의 것이 아닐 때__보내면__한도 확인 뒤 UUID와 삽입 전에 거절한다`(
        context: String,
    ) {
        // given
        val f = Fixture()
        f.contextAllowed = false

        // when
        val failure = assertThrows(NoteSendRejectedException::class.java) {
            f.app.send(7L, SendNote(8L, "body", REQUEST, null, context, 3L))
        }

        // then
        assertEquals(CONTEXT_AUTHOR, failure.reason())
        assertEquals(listOf<Any?>(context, 3L, 8L), f.contextArguments)
        assertEquals(0, f.generated)
        assertNull(f.inserted)
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        verify(exactly = 1) { f.clock.instant() }
        confirmVerified(f.members, f.clock)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "REPORT"])
    fun `유효한 답장과 맥락이 주어졌을 때__보내면__맥락보다 답장을 먼저 확인하고 새 행 모두에 첫 시각을 쓴다`(
        context: String,
    ) {
        // given
        val f = Fixture()

        // when
        val receipt = f.app.send(7L, SendNote(8L, "body", REQUEST, PARENT, context, 3L))

        // then
        assertEquals(Receipt(NOTE), receipt)
        assertEquals(
            listOf(
                "begin", "lock:7:8", "member:7", "member:8", "previous", "blocked", "clock:1", "rate:1", "rate:2",
                "clock:2", "reply", "context:$context", "uuid", "insert", "commit",
            ),
            f.events,
        )
        assertEquals(listOf<Any?>(7L, PARENT, NOW.plusSeconds(9)), f.replyArguments)
        assertEquals(listOf<Any?>(context, 3L, 8L), f.contextArguments)
        assertEquals(NOW, f.inserted!!.createdAt)
        assertEquals(NOW, f.notification!!.createdAt)
        assertEquals(PARENT, f.inserted!!.replyTo)
        assertEquals(context, f.inserted!!.contextType)
        assertEquals(3L, f.inserted!!.contextId)
        assertEquals(1, f.generated)
        assertEquals(
            if (context == "POST") {
                "1e732b23d6b7844b5d49c78cc32edef6b4bb5da067eafa9ac804c50407a788f0"
            } else {
                "ab8cb3f87a4d3677737a8722956b21ff559831d2ad069013299c36da06200a49"
            },
            f.inserted!!.requestHash,
        )
        verify(exactly = 1) { f.members.get(7L) }
        verify(exactly = 1) { f.members.get(8L) }
        verify(exactly = 2) { f.clock.instant() }
        confirmVerified(f.members, f.clock)
    }

    /** Policy callback fake, not database or transaction manager evidence. */
    private class Fixture {
        val events = ArrayList<String>()
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val inactive = HashSet<Long>()
        val rateTimes = ArrayList<Instant>()
        var previous: Optional<NoteSendStore.PreviousRequest> = Optional.empty()
        var parent: Optional<NoteSendStore.Reply> = Optional.of(NoteSendStore.Reply("INBOX", 8L))
        var previousArguments: List<Any?>? = null
        var replyArguments: List<Any?>? = null
        var contextArguments: List<Any?>? = null
        var active = false
        var blocked = false
        var contextAllowed = true
        var minuteCount = 0L
        var dayCount = 0L
        var generated = 0
        var samples = 0
        var inserted: NoteSendStore.NewNote? = null
        var notification: Notification? = null
        var unavailable: ContactMemberUnavailableException? = null
        val app: SendPrivateNote

        init {
            every { members.get(any()) } answers {
                assertTrue(active)
                val id: Long = firstArg()
                events.add("member:$id")
                if (id == 8L && unavailable != null) {
                    throw unavailable!!
                }
                ContactMember(id, if (id == 7L) " raw \n" else "target", !inactive.contains(id), inactive.contains(id))
            }
            every { clock.instant() } answers {
                assertTrue(active)
                samples++
                events.add("clock:$samples")
                if (samples == 1) NOW else NOW.plusSeconds(9)
            }
            val store = object : NoteSendStore {
                override fun inTransaction(action: Supplier<Receipt>): Receipt {
                    assertFalse(active)
                    active = true
                    events.add("begin")
                    try {
                        val result = action.get()
                        events.add("commit")
                        return result
                    } catch (failure: RuntimeException) {
                        events.add("rollback")
                        throw failure
                    } finally {
                        active = false
                    }
                }

                override fun lockMembers(firstMemberId: Long, secondMemberId: Long) {
                    assertTrue(active)
                    events.add("lock:$firstMemberId:$secondMemberId")
                }

                override fun request(senderId: Long, requestId: UUID): Optional<NoteSendStore.PreviousRequest> {
                    assertTrue(active)
                    events.add("previous")
                    previousArguments = listOf(senderId, requestId)
                    return previous
                }

                override fun blocked(firstMemberId: Long, secondMemberId: Long): Boolean {
                    assertTrue(active)
                    assertEquals(7L, firstMemberId)
                    assertEquals(8L, secondMemberId)
                    events.add("blocked")
                    return blocked
                }

                override fun rate(senderId: Long, since: Instant): Long {
                    assertTrue(active)
                    assertEquals(7L, senderId)
                    rateTimes.add(since)
                    events.add("rate:" + rateTimes.size)
                    return if (rateTimes.size == 1) minuteCount else dayCount
                }

                override fun reply(ownerId: Long, noteId: UUID, now: Instant): Optional<NoteSendStore.Reply> {
                    assertTrue(active)
                    events.add("reply")
                    replyArguments = listOf(ownerId, noteId, now)
                    return parent
                }

                override fun contextAuthor(contextType: String, contextId: Long, recipientId: Long): Boolean {
                    assertTrue(active)
                    events.add("context:$contextType")
                    contextArguments = listOf(contextType, contextId, recipientId)
                    return contextAllowed
                }

                override fun insert(note: NoteSendStore.NewNote, notification: Notification) {
                    assertTrue(active)
                    events.add("insert")
                    inserted = note
                    this@Fixture.notification = notification
                }
            }
            app = SendPrivateNote(
                members,
                store,
                clock,
                Supplier {
                    assertTrue(active)
                    events.add("uuid")
                    generated++
                    NOTE
                },
            )
        }
    }

    companion object {
        private val NOTE = UUID.fromString("f2900000-0000-0000-0000-000000000001")
        private val REQUEST = UUID.fromString("f2900000-0000-0000-0000-000000000002")
        private val PARENT = UUID.fromString("f2900000-0000-0000-0000-000000000003")
        private val NOW = Instant.parse("2028-02-29T15:00:00.123456789Z")
        private const val HASH = "6a4db528d8a9a2accbb88082a032deeb2c652357e5039f3e1975cf0f2361aff2"

        @JvmStatic
        fun invalidInputs(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0L, null, LOGIN),
                Arguments.of(-1L, request("hello\n😀"), LOGIN),
                Arguments.of(7L, null, INVALID_REQUEST),
                Arguments.of(7L, SendNote(null, "body", REQUEST, null, null, null), INVALID_REQUEST),
                Arguments.of(7L, SendNote(0L, "body", REQUEST, null, null, null), INVALID_REQUEST),
                Arguments.of(7L, SendNote(7L, "body", REQUEST, null, null, null), INVALID_REQUEST),
                Arguments.of(7L, SendNote(8L, "body", null, null, null, null), INVALID_REQUEST),
                Arguments.of(7L, SendNote(8L, null, REQUEST, null, null, null), INVALID_REQUEST),
                Arguments.of(7L, request(" \n\t "), INVALID_BODY),
                Arguments.of(7L, request("😀".repeat(5001)), INVALID_BODY),
                Arguments.of(7L, request("a\u0000b"), INVALID_BODY),
                Arguments.of(7L, SendNote(8L, "body", REQUEST, null, null, 3L), INVALID_CONTEXT),
                Arguments.of(7L, SendNote(8L, "body", REQUEST, null, "POST", null), INVALID_CONTEXT),
                Arguments.of(7L, SendNote(8L, "body", REQUEST, null, "OTHER", 3L), INVALID_CONTEXT),
                Arguments.of(7L, SendNote(8L, "body", REQUEST, null, "REPORT", 0L), INVALID_CONTEXT),
                Arguments.of(7L, SendNote(8L, " ", null, null, "OTHER", 0L), INVALID_REQUEST),
            )

        @JvmStatic
        fun validBodies(): Stream<Arguments> =
            Stream.of(
                Arguments.of(" \thello\n😀 \n", "hello\n😀", HASH),
                Arguments.of(
                    "\t" + "😀".repeat(5000) + "\n",
                    "😀".repeat(5000),
                    "9ce4ddef667f7702efb05967b985a0283dca7e3f8812fd9e0c3d3ae80d4254b1",
                ),
            )

        @JvmStatic
        fun previousRequests(): Stream<Arguments> =
            Stream.of(
                Arguments.of(NoteSendStore.PreviousRequest(null, "different"), GONE),
                Arguments.of(NoteSendStore.PreviousRequest(NOTE, null), GONE),
                Arguments.of(NoteSendStore.PreviousRequest(NOTE, "different"), CONFLICT),
            )

        @JvmStatic
        fun rejectedReplies(): Stream<Arguments> =
            Stream.of(
                Arguments.of(Optional.empty<NoteSendStore.Reply>(), NOT_FOUND),
                Arguments.of(Optional.of(NoteSendStore.Reply("SENT", 8L)), REPLY_MISMATCH),
                Arguments.of(Optional.of(NoteSendStore.Reply("INBOX", null)), REPLY_MISMATCH),
                Arguments.of(Optional.of(NoteSendStore.Reply("INBOX", 9L)), REPLY_MISMATCH),
            )

        private fun request(body: String): SendNote = SendNote(8L, body, REQUEST, null, null, null)
    }
}
