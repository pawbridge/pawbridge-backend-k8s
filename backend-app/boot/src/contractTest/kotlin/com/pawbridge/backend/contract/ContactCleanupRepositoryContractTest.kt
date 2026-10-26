package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.note.infrastructure.JdbcContactMemberCleanupStore
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Removing a member from private notes and chats: what stays for the counterpart, what goes, and the rollback. */
class ContactCleanupRepositoryContractTest : ApiContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var writeTx: TransactionTemplate

    private val pushed = ArrayList<Long>()
    private lateinit var store: JdbcContactMemberCleanupStore
    private val sharedNote = UUID.randomUUID()
    private val ownNote = UUID.randomUUID()
    private val sharedRoom = UUID.randomUUID()
    private val ownRoom = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        pushed.clear()
        store = JdbcContactMemberCleanupStore(write, writeTx) { pushed.add(it) }
        for (member in listOf(GONE, STAYS)) {
            execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?)", member)
        }
        seedNote(sharedNote, GONE, STAYS, both = true)
        seedNote(ownNote, GONE, STAYS, both = false)
        execute("UPDATE pawbridge_community.private_notes SET context_type = 'POST', context_id = 5")
        execute(
            "INSERT INTO pawbridge_community.private_note_requests(sender_id, request_id, request_hash, note_id, created_at) " +
                "VALUES (?, gen_random_uuid(), ?, ?, now())",
            GONE, "h".repeat(64), sharedNote,
        )
        execute(
            "INSERT INTO pawbridge_community.private_note_requests(sender_id, request_id, request_hash, note_id, created_at) " +
                "VALUES (?, gen_random_uuid(), ?, ?, now())",
            STAYS, "k".repeat(64), sharedNote,
        )
        seedRoom(sharedRoom, GONE, STAYS)
        seedRoom(ownRoom, GONE, null)
    }

    @Test
    fun `쪽지와 채팅을 가진 회원일 때__제거하면__상대에게 남는 데이터는 익명으로 남기고 나머지는 지우며 커밋 뒤에 한 번 알린다`() {
        // given
        var guarded = 0

        // when
        store.remove(GONE) { guarded++ }

        // then
        assertThat(guarded).isOne()
        assertThat(pushed).isEqualTo(listOf(GONE))
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id = ?", GONE)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id = ?", STAYS)).isOne()
        assertThat(scalar("SELECT coalesce(sender_id::text, 'null') || ':' || coalesce(context_type, 'null') FROM pawbridge_community.private_notes WHERE note_id = ?", sharedNote))
            .`as`("Still read by the counterpart, no longer tied to the removed member or a context").isEqualTo("null:null")
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id = ? AND member_id = ?", sharedNote, STAYS)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id = ?", ownNote)).`as`("No mailbox left").isZero()
        assertThat(scalar("SELECT request_hash FROM pawbridge_community.private_note_requests WHERE sender_id = ?", STAYS))
            .`as`("The counterpart's replay key no longer matches the withdrawn note's content").isNull()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_room_members WHERE member_id = ?", GONE)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id = ?", GONE)).isZero()
        assertThat(scalar("SELECT sender_id FROM pawbridge_community.member_chat_messages WHERE room_id = ?", sharedRoom)).isNull()
        assertThat(scalar("SELECT coalesce(first_member_id::text, 'null') || ':' || second_member_id FROM pawbridge_community.member_chat_rooms WHERE room_id = ?", sharedRoom))
            .isEqualTo("null:$STAYS")
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_rooms WHERE room_id = ?", ownRoom)).`as`("An empty room is removed").isZero()
    }

    @Test
    fun `삭제 대기 확인이 실패할 때__제거하면__아무것도 바뀌지 않고 알리지 않는다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { store.remove(GONE) { throw IllegalStateException("not pending") } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(ContractPostgres.snapshot() == before).isTrue()
        assertThat(pushed).isEmpty()
    }

    @Test
    fun `알림 처리가 실패할 때__제거하면__커밋은 유지하고 예외를 밖으로 내지 않는다`() {
        // given
        val failing = JdbcContactMemberCleanupStore(write, writeTx) { throw IllegalStateException("push down") }

        // when
        failing.remove(GONE) {}

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id = ?", GONE)).isZero()
    }

    private fun seedNote(note: UUID, sender: Long, recipient: Long, both: Boolean) {
        execute(
            "INSERT INTO pawbridge_community.private_notes(note_id, sender_id, recipient_id, body, created_at, expires_at) " +
                "VALUES (?, ?, ?, '정리 본문', now(), now() + interval '1 day')",
            note, sender, recipient,
        )
        execute("INSERT INTO pawbridge_community.private_note_mailboxes(member_id, note_id, direction, created_at) VALUES (?, ?, 'SENT', now())", sender, note)
        if (both) {
            execute("INSERT INTO pawbridge_community.private_note_mailboxes(member_id, note_id, direction, created_at) VALUES (?, ?, 'INBOX', now())", recipient, note)
        }
    }

    private fun seedRoom(room: UUID, first: Long, second: Long?) {
        execute(
            "INSERT INTO pawbridge_community.member_chat_rooms(room_id, first_member_id, second_member_id, latest_sequence) VALUES (?, ?, ?, 1)",
            room, minOf(first, second ?: Long.MAX_VALUE), second?.let { maxOf(first, it) },
        )
        execute("INSERT INTO pawbridge_community.member_chat_room_members(room_id, member_id) VALUES (?, ?)", room, first)
        if (second != null) {
            execute("INSERT INTO pawbridge_community.member_chat_room_members(room_id, member_id) VALUES (?, ?)", room, second)
        }
        execute(
            "INSERT INTO pawbridge_community.member_chat_messages(room_id, sequence, sender_id, body, created_at, expires_at) " +
                "VALUES (?, 1, ?, '채팅', now(), now() + interval '1 day')",
            room, first,
        )
        execute(
            "INSERT INTO pawbridge_community.member_chat_requests(sender_id, request_id, request_hash, room_id, sequence, created_at) " +
                "VALUES (?, gen_random_uuid(), repeat('a', 64), ?, 1, now())",
            first, room,
        )
    }

    private companion object {
        const val GONE = 9_924_001L
        const val STAYS = 9_924_002L
    }
}
