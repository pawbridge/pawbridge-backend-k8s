package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.chat.infrastructure.JdbcMemberChat
import com.pawbridge.backend.community.internal.chat.infrastructure.JdbcMemberChatRepository
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Member to member chat against the real schema.
 *
 * The chat has no HTTP or socket route in the shared context (it needs Redis for live delivery), so the service is
 * built here from the application's own write pool, note send store and member query, the way the configuration builds
 * it. Only the live delivery hint is replaced by a recorder, which is also what shows that signals leave after the commit.
 */
class MemberChatContractTest : ApiContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    @Qualifier("writeTransactionManager")
    private lateinit var manager: JdbcTransactionManager

    @Autowired
    private lateinit var contact: NoteSendStore

    @Autowired
    private lateinit var users: ContactMemberQuery

    private val signals = ArrayList<Signal>()
    private val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private lateinit var chat: JdbcMemberChat

    @BeforeEach
    fun seedMembers() {
        signals.clear()
        seedUser(ALICE, "앨리스")
        seedUser(BOB, "밥")
        seedUser(CAROL, "캐롤")
        val transaction = TransactionTemplate(manager)
        chat = JdbcMemberChat(JdbcMemberChatRepository(jdbc), contact, users, MemberChatHints { signals.add(it) }, clock, transaction)
    }

    @Test
    fun `처음 보내는 사이일 때__보내면__작은 번호 순서의 방 하나와 멤버 둘과 메시지와 요청이 생기고 커밋 뒤에 두 신호를 낸다`() {
        // given
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        // when
        val one = chat.send(BOB, Send(ALICE, first, "  안녕하세요  ", null, null))
        val two = chat.send(ALICE, Send(BOB, second, "답장입니다", null, null))

        // then
        assertThat(one.sequence).isEqualTo(1)
        assertThat(two.sequence).isEqualTo(2)
        assertThat(two.roomId).`as`("The same pair always shares one room").isEqualTo(one.roomId)
        val room = MAPPER.readTree(scalar("SELECT to_jsonb(t)::text FROM pawbridge_community.member_chat_rooms t WHERE room_id = ?::uuid", one.roomId.toString()))
        assertThat(room.get("first_member_id").asLong()).isEqualTo(ALICE)
        assertThat(room.get("second_member_id").asLong()).isEqualTo(BOB)
        assertThat(room.get("latest_sequence").asLong()).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_room_members WHERE room_id = ?::uuid", one.roomId.toString())).isEqualTo(2)
        assertThat(
            ContractPostgres.admin.queryForList(
                "SELECT sender_id || ':' || body || ':' || (expires_at - created_at) FROM pawbridge_community.member_chat_messages " +
                    "WHERE room_id = ?::uuid ORDER BY sequence",
                String::class.java,
                one.roomId.toString(),
            ),
        ).isEqualTo(listOf("$BOB:안녕하세요:365 days", "$ALICE:답장입니다:365 days"))
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id = ?", BOB)).isOne()
        assertThat(signals).isEqualTo(
            listOf(
                Signal(BOB, "SENT", one.roomId, 1), Signal(ALICE, "MESSAGE", one.roomId, 1),
                Signal(ALICE, "SENT", one.roomId, 2), Signal(BOB, "MESSAGE", one.roomId, 2),
            ),
        )
    }

    @Test
    fun `두 사람이 동시에 처음 보낼 때__보내면__방은 하나이고 순번은 겹치지 않는다`() {
        // given
        val threads = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)

        // when
        val receipts = (0 until 4).map { index ->
            val sender = if (index % 2 == 0) ALICE else BOB
            val recipient = if (index % 2 == 0) BOB else ALICE
            threads.submit<Long> {
                start.await()
                chat.send(sender, Send(recipient, UUID.randomUUID(), "동시 $index", null, null)).sequence
            }
        }
        start.countDown()
        receipts.forEach { it.get(20, TimeUnit.SECONDS) }
        threads.shutdown()

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_rooms")).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_room_members")).isEqualTo(2)
        assertThat(scalar("SELECT string_agg(sequence::text, ',' ORDER BY sequence) FROM pawbridge_community.member_chat_messages")).isEqualTo("1,2,3,4")
        assertThat(scalar("SELECT latest_sequence FROM pawbridge_community.member_chat_rooms")).isEqualTo("4")
    }

    @Test
    fun `같은 전송 키로 다시 보낼 때__보내면__같은 영수증을 주고 새 행도 신호도 없으며 내용이 다르거나 보관이 끝났으면 거부한다`() {
        // given
        val key = UUID.randomUUID()
        val receipt = chat.send(ALICE, Send(BOB, key, "한 번만", null, null))
        signals.clear()
        val before = ContractPostgres.snapshot()

        // when
        val replay = chat.send(ALICE, Send(BOB, key, "한 번만", null, null))

        // then
        assertThat(replay).isEqualTo(receipt)
        assertThat(ContractPostgres.snapshot() == before).`as`("A replay writes nothing").isTrue()
        assertThat(signals).isEmpty()
        assertRejected(409) { chat.send(ALICE, Send(BOB, key, "다른 내용", null, null)) }
        execute("UPDATE pawbridge_community.member_chat_messages SET created_at = ?::timestamptz - interval '1 hour', expires_at = ?::timestamptz", NOW.toString(), NOW.minusSeconds(1).toString())
        assertRejected(410) { chat.send(ALICE, Send(BOB, key, "한 번만", null, null)) }
    }

    @Test
    fun `차단과 한도와 관련 글 작성자가 맞지 않을 때__보내면__403과 429와 400이고 아무것도 쓰지 않는다`() {
        // given
        seedPost(POST, BOB)
        seedPost(POST + 1, CAROL)
        execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?), (?)", ALICE, CAROL)
        execute(
            "INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at) VALUES (?, ?, now())",
            CAROL, ALICE,
        )
        val afterBlockSeed = ContractPostgres.snapshot()

        // when
        // then
        assertRejected(403) { chat.send(ALICE, Send(CAROL, UUID.randomUUID(), "차단됨", null, null)) }
        assertRejected(403) { chat.send(CAROL, Send(ALICE, UUID.randomUUID(), "차단함", null, null)) }
        assertRejected(400) { chat.send(ALICE, Send(BOB, UUID.randomUUID(), "남의 글", "POST", POST + 1)) }
        assertRejected(400) { chat.send(ALICE, Send(ALICE, UUID.randomUUID(), "나에게", null, null)) }
        assertRejected(400) { chat.send(ALICE, Send(BOB, UUID.randomUUID(), "   ", null, null)) }
        assertRejected(400) { chat.send(ALICE, Send(BOB, UUID.randomUUID(), "가".repeat(2001), null, null)) }
        assertRejected(400) { chat.send(ALICE, Send(BOB, UUID.randomUUID(), "맥락 쌍", "POST", null)) }
        assertThat(ContractPostgres.snapshot() == afterBlockSeed).`as`("Every refusal left the tables as they were").isTrue()
        assertThat(signals).isEmpty()

        // when the context belongs to the recipient
        val accepted = chat.send(ALICE, Send(BOB, UUID.randomUUID(), "내 글 관련", "POST", POST))
        val stored = scalar("SELECT context_type || ':' || context_id FROM pawbridge_community.member_chat_messages WHERE room_id = ?::uuid", accepted.roomId.toString())
        assertThat(stored).isEqualTo("POST:$POST")
    }

    @Test
    fun `분당 60건 또는 하루 1000건을 보냈을 때__보내면__429이고 한도 안이면 보낼 수 있다`() {
        // given
        val minute = NOW.minusSeconds(30)
        val earlierToday = NOW.minusSeconds(3 * 3600)
        val yesterday = NOW.minusSeconds(20 * 3600)
        seedRequests(CAROL, 59, minute)
        seedRequests(BOB, 999, earlierToday)
        seedRequests(BOB, 50, yesterday)

        // when
        // then
        chat.send(CAROL, Send(ALICE, UUID.randomUUID(), "예순 번째", null, null))
        assertRejected(429) { chat.send(CAROL, Send(ALICE, UUID.randomUUID(), "예순한 번째", null, null)) }
        chat.send(BOB, Send(ALICE, UUID.randomUUID(), "천 번째", null, null))
        assertRejected(429) { chat.send(BOB, Send(ALICE, UUID.randomUUID(), "천한 번째", null, null)) }
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_messages")).isEqualTo(2)
    }

    @Test
    fun `읽지 않은 메시지가 있는 방 둘일 때__방과 알림을 읽으면__최신 방 순서와 안 읽음 수와 미리보기 유무를 준다`() {
        // given
        val withBob = chat.send(ALICE, Send(BOB, UUID.randomUUID(), "밥에게 1", null, null)).roomId!!
        chat.send(ALICE, Send(BOB, UUID.randomUUID(), "밥에게 2", null, null))
        chat.send(BOB, Send(ALICE, UUID.randomUUID(), "밥의 답", null, null))
        val withCarol = chat.send(CAROL, Send(ALICE, UUID.randomUUID(), "캐롤의 말", null, null)).roomId!!
        execute("UPDATE pawbridge_community.member_chat_messages SET created_at = created_at + (sequence || ' minutes')::interval WHERE room_id = ?::uuid", withBob.toString())

        // when
        val rooms = chat.rooms(ALICE, null)
        val notifications = chat.notifications(ALICE, null)
        val room = chat.room(ALICE, withBob)

        // then
        assertThat(rooms.content.map { it.roomId }).`as`("Newest message first").isEqualTo(listOf(withBob, withCarol))
        assertThat(rooms.content.map { it.unread }).isEqualTo(listOf(true, true))
        assertThat(rooms.content.map { it.preview }).isEqualTo(listOf("밥의 답", "캐롤의 말"))
        assertThat(rooms.content.map { it.counterpartNickname }).isEqualTo(listOf("밥", "캐롤"))
        assertThat(notifications.unreadCount).isEqualTo(2)
        assertThat(notifications.content.map { it.preview }).`as`("Notifications carry no message text").containsOnlyNulls()
        assertThat(room.latestSequence).isEqualTo(3)
        assertThat(room.counterpartId).isEqualTo(BOB)
        assertThat(room.unread).isTrue()
        assertThat(room.canSend).isTrue()
        assertThat(room.preview).isEqualTo("밥의 답")
        assertThat(chat.existingRoom(ALICE, BOB).roomId).isEqualTo(withBob)
        assertThat(chat.existingRoom(ALICE, 999_999L).roomId).isNull()
        assertRejected(404) { chat.room(CAROL, withBob) }
    }

    @Test
    fun `방이 열한 개 이상이고 메시지가 쉰 개를 넘을 때__방과 메시지를 읽으면__열 개와 쉰 개씩 커서로 이어 준다`() {
        // given
        val rooms = (1..12).map { index -> seedRoom(ALICE, 8_000_000L + index, 1, NOW.minusSeconds(index * 60L)) }
        val crowded = seedRoom(ALICE, BOB, 60, NOW)

        // when
        val first = chat.rooms(ALICE, null)
        val second = chat.rooms(ALICE, first.nextCursor)
        val newest = chat.messages(ALICE, crowded, null, null)
        val older = chat.messages(ALICE, crowded, newest.nextCursor, null)
        val forward = chat.messages(ALICE, crowded, null, 0)
        val rest = chat.messages(ALICE, crowded, null, forward.nextCursor)

        // then
        assertThat(first.content).hasSize(10)
        assertThat(first.nextCursor).isEqualTo(first.content[9].roomId)
        assertThat(first.content.first().roomId).isEqualTo(crowded)
        assertThat(second.content.map { it.roomId }).isEqualTo(listOf(rooms[9], rooms[10], rooms[11]))
        assertThat(second.nextCursor).isNull()
        assertThat(newest.content.map { it.sequence }).isEqualTo((11L..60L).toList())
        assertThat(newest.nextCursor).isEqualTo(11L)
        assertThat(older.content.map { it.sequence }).isEqualTo((1L..10L).toList())
        assertThat(older.nextCursor).isNull()
        assertThat(forward.content.map { it.sequence }).isEqualTo((1L..50L).toList())
        assertThat(forward.nextCursor).isEqualTo(50L)
        assertThat(rest.content.map { it.sequence }).isEqualTo((51L..60L).toList())
        assertRejected(400) { chat.messages(ALICE, crowded, 5L, 5L) }
        assertRejected(400) { chat.messages(ALICE, crowded, 0L, null) }
    }

    @Test
    fun `읽음과 숨김을 요청할 때__읽으면__읽은 위치는 줄지 않고 범위 밖은 400이며 숨기면 새 메시지가 올 때까지 목록에서 빠진다`() {
        // given
        val room = chat.send(BOB, Send(ALICE, UUID.randomUUID(), "하나", null, null)).roomId!!
        chat.send(BOB, Send(ALICE, UUID.randomUUID(), "둘", null, null))
        signals.clear()

        // when
        chat.read(ALICE, room, 2)
        chat.read(ALICE, room, 1)
        val readThrough = readThrough(ALICE, room)
        val unreadAfterRead = chat.notifications(ALICE, null).unreadCount
        val signalsAfterRead = ArrayList(signals)
        assertRejected(400) { chat.read(ALICE, room, 3) }
        assertRejected(400) { chat.read(ALICE, room, -1) }
        assertRejected(404) { chat.read(CAROL, room, 1) }
        chat.hide(ALICE, room)
        val hidden = chat.rooms(ALICE, null).content
        chat.send(BOB, Send(ALICE, UUID.randomUUID(), "셋", null, null))
        val shownAgain = chat.rooms(ALICE, null).content

        // then
        assertThat(readThrough).isEqualTo(2)
        assertThat(unreadAfterRead).isZero()
        assertThat(signalsAfterRead).isEqualTo(listOf(Signal(ALICE, "READ", room, 2), Signal(BOB, "READ", room, 2), Signal(ALICE, "READ", room, 1), Signal(BOB, "READ", room, 1)))
        assertThat(scalar("SELECT hidden_through FROM pawbridge_community.member_chat_room_members WHERE room_id = ?::uuid AND member_id = ?", room.toString(), ALICE))
            .isEqualTo("2")
        assertThat(hidden).isEmpty()
        assertThat(shownAgain.map { it.roomId }).isEqualTo(listOf(room))
        assertThat(scalar("SELECT hidden_through FROM pawbridge_community.member_chat_room_members WHERE room_id = ?::uuid AND member_id = ?", room.toString(), BOB))
            .`as`("Hiding is per member").isEqualTo("-1")
    }

    @Test
    fun `보관 기간이 지난 메시지와 1년 지난 전송 기록이 있을 때__정리하면__만료 건만 300건씩 지운다`() {
        // given
        val room = seedRoom(ALICE, BOB, 2, NOW)
        expireAt(room, 1, NOW.minusSeconds(1))
        expireAt(room, 2, NOW.plusSeconds(1))
        val expiredRoom = seedRoom(ALICE, CAROL, 301, NOW)
        execute(
            "UPDATE pawbridge_community.member_chat_messages SET created_at = ?::timestamptz - interval '1 hour', " +
                "expires_at = ?::timestamptz WHERE room_id = ?::uuid",
            NOW.toString(), NOW.minusSeconds(5).toString(), expiredRoom.toString(),
        )
        seedRequests(ALICE, 1, NOW.minusSeconds(400L * 24 * 3600))
        seedRequests(BOB, 1, NOW.minusSeconds(300L * 24 * 3600))

        // when
        chat.expire()
        val afterFirst = count("SELECT count(*) FROM pawbridge_community.member_chat_messages WHERE expires_at <= ?::timestamptz", NOW.toString())
        chat.expire()

        // then
        assertThat(afterFirst).`as`("302 expired messages, 300 per pass").isEqualTo(2)
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_messages WHERE expires_at <= ?::timestamptz", NOW.toString())).isZero()
        assertThat(scalar("SELECT string_agg(sequence::text, ',') FROM pawbridge_community.member_chat_messages WHERE room_id = ?::uuid", room.toString())).isEqualTo("2")
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id = ?", ALICE)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id = ?", BOB)).isOne()
    }

    private fun assertRejected(status: Int, action: () -> Unit) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MemberChatRejectedException::class.java) {
            assertThat(it.status()).isEqualTo(status)
        }
    }

    private fun expireAt(room: UUID, sequence: Int, at: Instant) {
        execute(
            "UPDATE pawbridge_community.member_chat_messages SET created_at = ?::timestamptz - interval '1 hour', " +
                "expires_at = ?::timestamptz WHERE room_id = ?::uuid AND sequence = ?",
            at.toString(), at.toString(), room.toString(), sequence,
        )
    }

    private fun readThrough(member: Long, room: UUID): Long =
        count("SELECT read_through FROM pawbridge_community.member_chat_room_members WHERE member_id = ? AND room_id = ?::uuid", member, room.toString())

    private fun seedRequests(sender: Long, number: Int, createdAt: Instant) {
        execute(
            "INSERT INTO pawbridge_community.member_chat_requests(sender_id, request_id, request_hash, room_id, sequence, created_at) " +
                "SELECT ?, gen_random_uuid(), repeat('a', 64), gen_random_uuid(), 1, ?::timestamptz FROM generate_series(1, ?)",
            sender, createdAt.toString(), number,
        )
    }

    /** A room with [messages] messages from the counterpart, one second apart ending at [last], all unexpired. */
    private fun seedRoom(owner: Long, counterpart: Long, messages: Int, last: Instant): UUID {
        val room = UUID.randomUUID()
        execute(
            "INSERT INTO pawbridge_community.member_chat_rooms(room_id, first_member_id, second_member_id, latest_sequence) VALUES (?, ?, ?, ?)",
            room, minOf(owner, counterpart), maxOf(owner, counterpart), messages,
        )
        execute("INSERT INTO pawbridge_community.member_chat_room_members(room_id, member_id) VALUES (?, ?), (?, ?)", room, owner, room, counterpart)
        execute(
            "INSERT INTO pawbridge_community.member_chat_messages(room_id, sequence, sender_id, body, created_at, expires_at) " +
                "SELECT ?, s, ?, '메시지 ' || s, ?::timestamptz - ((? - s) || ' seconds')::interval, ?::timestamptz + interval '1 day' " +
                "FROM generate_series(1, ?) s",
            room, counterpart, last.toString(), messages, NOW.toString(), messages,
        )
        return room
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-10T05:00:00Z")
        const val ALICE = 9_912_001L
        const val BOB = 9_912_002L
        const val CAROL = 9_912_003L
        const val POST = 9_912_100L
    }
}
