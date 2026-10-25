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
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import java.util.stream.Stream

class SendPrivateNoteTest {

    @ParameterizedTest
    @MethodSource("invalidInputs")
    fun given__invalid_id_request_body_or_context__when__sending__then__reject_before_transaction_user_clock_or_uuid(
        current: Long,
        request: SendNote?,
        reason: NoteSendRejectedException.Reason,
    ) {
        val f = Fixture()

        assertEquals(
            reason,
            assertThrows(NoteSendRejectedException::class.java) { f.app.send(current, request) }.reason(),
        )
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.generated)
        verifyNoInteractions(f.members, f.clock)
    }

    @ParameterizedTest
    @MethodSource("validBodies")
    fun given__valid_body_and_active_members__when__sending__then__preserve_raw_text_hash_policy_order_seoul_day_and_utc_year(
        raw: String,
        stripped: String,
        hash: String,
    ) {
        val f = Fixture()
        f.minuteCount = 9
        f.dayCount = 99

        assertEquals(Receipt(NOTE), f.app.send(7L, request(raw)))

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
        verify(f.clock).instant()
        verify(f.members).get(7L)
        verify(f.members).get(8L)
        verifyNoMoreInteractions(f.members, f.clock)
        assertFalse(f.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["hello\n😀", " \thello\n😀\n "])
    fun given__the_same_stripped_request_was_already_stored__when__replaying__then__check_members_and_return_original_id_before_block_time_or_new_work(
        body: String,
    ) {
        val f = Fixture()
        f.previous = Optional.of(NoteSendStore.PreviousRequest(PARENT, HASH))
        f.blocked = true

        assertEquals(Receipt(PARENT), f.app.send(7L, request(body)))

        assertEquals(listOf("begin", "lock:7:8", "member:7", "member:8", "previous", "commit"), f.events)
        assertEquals(listOf<Any?>(7L, REQUEST), f.previousArguments)
        assertNull(f.inserted)
        assertEquals(0, f.generated)
        verifyNoInteractions(f.clock)
        verify(f.members).get(7L)
        verify(f.members).get(8L)
        verifyNoMoreInteractions(f.members)
    }

    @ParameterizedTest
    @MethodSource("previousRequests")
    fun given__gone_or_conflicting_request__when__sending__then__prefer_gone_before_hash_conflict_and_stop_before_time(
        previous: NoteSendStore.PreviousRequest,
        reason: NoteSendRejectedException.Reason,
    ) {
        val f = Fixture()
        f.previous = Optional.of(previous)

        assertEquals(
            reason,
            assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request("hello\n😀")) }.reason(),
        )

        assertEquals(listOf("begin", "lock:7:8", "member:7", "member:8", "previous", "rollback"), f.events)
        assertNull(f.inserted)
        assertEquals(0, f.generated)
        verifyNoInteractions(f.clock)
    }

    @ParameterizedTest
    @ValueSource(longs = [7L, 8L])
    fun given__current_or_recipient_is_inactive__when__sending__then__reject_in_user_order_before_looking_up_a_previous_request(
        inactive: Long,
    ) {
        val f = Fixture()
        f.inactive.add(inactive)
        f.previous = Optional.of(NoteSendStore.PreviousRequest(null, null))

        assertEquals(
            INACTIVE,
            assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request("body")) }.reason(),
        )

        val expected = ArrayList(listOf("begin", "lock:7:8", "member:7"))
        if (inactive == 8L) {
            expected.add("member:8")
        }
        expected.add("rollback")
        assertEquals(expected, f.events)
        assertEquals(0, f.generated)
        verifyNoInteractions(f.clock)
    }

    @Test
    fun given__recipient_user_lookup_is_unavailable__when__sending__then__propagate_the_same_typed_failure_before_request_or_clock() {
        val f = Fixture()
        val failure = ContactMemberUnavailableException(IllegalStateException("private-user-detail"))
        f.unavailable = failure

        assertSame(
            failure,
            assertThrows(ContactMemberUnavailableException::class.java) { f.app.send(7L, request("body")) },
        )

        assertEquals(listOf("begin", "lock:7:8", "member:7", "member:8", "rollback"), f.events)
        assertEquals(0, f.generated)
        verifyNoInteractions(f.clock)
    }

    @Test
    fun given__new_request_is_blocked__when__sending__then__stop_before_sampling_time_or_querying_limits() {
        val f = Fixture()
        f.blocked = true

        assertEquals(
            BLOCKED,
            assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request("body")) }.reason(),
        )

        assertEquals(
            listOf("begin", "lock:7:8", "member:7", "member:8", "previous", "blocked", "rollback"),
            f.events,
        )
        assertEquals(0, f.generated)
        verifyNoInteractions(f.clock)
    }

    @ParameterizedTest
    @ValueSource(strings = ["minute", "day"])
    fun given__minute_or_day_limit_is_reached__when__sending__then__short_circuit_inclusive_queries_before_reply_context_or_uuid(
        limit: String,
    ) {
        val f = Fixture()
        f.minuteCount = if (limit == "minute") 10 else 9
        f.dayCount = 100
        val request = SendNote(8L, "body", REQUEST, PARENT, "POST", 3L)

        assertEquals(
            RATE_LIMIT,
            assertThrows(NoteSendRejectedException::class.java) { f.app.send(7L, request) }.reason(),
        )

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
        verify(f.clock).instant()
        verifyNoMoreInteractions(f.clock)
    }

    @ParameterizedTest
    @MethodSource("rejectedReplies")
    fun given__missing_or_mismatching_reply__when__sending__then__sample_reply_time_and_reject_before_context_or_uuid(
        parent: Optional<NoteSendStore.Reply>,
        reason: NoteSendRejectedException.Reason,
    ) {
        val f = Fixture()
        f.parent = parent

        assertEquals(
            reason,
            assertThrows(NoteSendRejectedException::class.java) {
                f.app.send(7L, SendNote(8L, "body", REQUEST, PARENT, "POST", 3L))
            }.reason(),
        )

        assertEquals(listOf<Any?>(7L, PARENT, NOW.plusSeconds(9)), f.replyArguments)
        assertFalse(f.events.contains("context:POST"))
        assertEquals(0, f.generated)
        assertNull(f.inserted)
        verify(f.clock, times(2)).instant()
        verifyNoMoreInteractions(f.clock)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "REPORT"])
    fun given__context_does_not_belong_to_recipient__when__sending__then__reject_after_rate_before_uuid_or_insert(
        context: String,
    ) {
        val f = Fixture()
        f.contextAllowed = false

        assertEquals(
            CONTEXT_AUTHOR,
            assertThrows(NoteSendRejectedException::class.java) {
                f.app.send(7L, SendNote(8L, "body", REQUEST, null, context, 3L))
            }.reason(),
        )

        assertEquals(listOf<Any?>(context, 3L, 8L), f.contextArguments)
        assertEquals(0, f.generated)
        assertNull(f.inserted)
        verify(f.clock).instant()
        verifyNoMoreInteractions(f.clock)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "REPORT"])
    fun given__valid_reply_and_context__when__sending__then__check_reply_before_context_and_keep_the_first_instant_for_all_new_rows(
        context: String,
    ) {
        val f = Fixture()

        assertEquals(Receipt(NOTE), f.app.send(7L, SendNote(8L, "body", REQUEST, PARENT, context, 3L)))

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
        verify(f.clock, times(2)).instant()
        verifyNoMoreInteractions(f.clock)
    }

    /** Policy callback fake, not database or transaction manager evidence. */
    private class Fixture {
        val events = ArrayList<String>()
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
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
            whenever(members.get(anyLong())).thenAnswer { call ->
                assertTrue(active)
                val id: Long = call.getArgument(0)
                events.add("member:$id")
                if (id == 8L && unavailable != null) {
                    throw unavailable!!
                }
                ContactMember(id, if (id == 7L) " raw \n" else "target", !inactive.contains(id), inactive.contains(id))
            }
            whenever(clock.instant()).thenAnswer {
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
