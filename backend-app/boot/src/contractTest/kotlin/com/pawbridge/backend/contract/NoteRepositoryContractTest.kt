package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteDeletion
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import com.pawbridge.backend.community.internal.note.application.NoteSendStore.NewNote
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockListReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteDeletion
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteNotificationReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteReadMutation
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteSendStore
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteListReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteReader
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * The private note repositories called directly on the application's pools: what each query reads and writes in the
 * real tables, and the branches the database result decides (expired, not owned, already blocked, duplicate request).
 */
class NoteRepositoryContractTest : NoteContractBase() {
    override val ids: LongRange = 9_920_000L..9_920_999L

    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var writeTx: TransactionTemplate

    @Autowired
    @Qualifier("readJdbc")
    private lateinit var read: JdbcTemplate

    @Autowired
    @Qualifier("readOnlyTransaction")
    private lateinit var readTx: TransactionTemplate

    private val resyncs = ArrayList<Long>()
    private val hints = NoteChangeHints { resyncs.add(it) }

    @BeforeEach
    fun seedMembers() {
        resyncs.clear()
        for (id in listOf(ME, YOU, THIRD)) {
            seedMember(id)
        }
    }

    // ---- blocks ------------------------------------------------------------------------------------------

    @Test
    fun `서로 다른 두 회원일 때__차단을 만들면__한 방향 행 하나가 시각과 함께 생기고 다시 만들어도 처음 시각을 지킨다`() {
        // given
        val creation = JdbcNoteBlockCreation(write, writeTx)
        val first = Instant.parse("2026-03-01T00:00:00.123456Z")
        val calls = ArrayList<Int>()

        // when
        creation.create(YOU, ME) { calls.add(1); first }
        creation.create(YOU, ME) { calls.add(2); first.plusSeconds(60) }

        // then
        assertThat(calls).isEqualTo(listOf(1, 2))
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?", YOU, ME)).isEqualTo("1")
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?", ME, YOU)).isEqualTo("0")
        assertThat(scalar("SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') FROM pawbridge_community.private_note_blocks"))
            .`as`("The first block time stays").isEqualTo("2026-03-01T00:00:00.123456")
    }

    @Test
    fun `내가 건 차단과 상대가 건 차단이 있을 때__차단을 풀면__내가 건 행만 지우고 가드는 잠금 뒤에 한 번 돈다`() {
        // given
        seedBlock(ME, YOU)
        seedBlock(YOU, ME)
        var guards = 0

        // when
        JdbcNoteBlockRemoval(write, writeTx).remove(ME, YOU) { guards++ }
        JdbcNoteBlockRemoval(write, writeTx).remove(ME, THIRD) { guards++ }

        // then
        assertThat(guards).isEqualTo(2)
        assertThat(scalar("SELECT string_agg(member_id || '>' || blocked_id, ',') FROM pawbridge_community.private_note_blocks")).isEqualTo("$YOU>$ME")
    }

    @Test
    fun `한쪽만 건 차단이 있을 때__차단 여부를 읽으면__어느 방향이든 참이고 관계 없는 쌍은 거짓이다`() {
        // given
        seedBlock(YOU, ME)
        val reader = JdbcNoteBlockReader(read, readTx)

        // when
        // then
        assertThat(reader.blocked(ME, YOU)).isTrue()
        assertThat(reader.blocked(YOU, ME)).isTrue()
        assertThat(reader.blocked(ME, THIRD)).isFalse()
    }

    @Test
    fun `차단이 열한 개일 때__목록을 읽으면__최신 차단부터 열 개씩 끊고 개수는 전체이며 남의 차단은 세지 않는다`() {
        // given
        for (offset in 1..11) {
            seedMember(ME + 100 + offset)
            seedBlock(ME, ME + 100 + offset, "2026-03-01T00:00:%02dZ".format(offset))
        }
        seedBlock(YOU, THIRD)
        val reader = JdbcNoteBlockListReader(read, readTx)

        // when
        val first = reader.read(ME, 0)
        val second = reader.read(ME, 1)

        // then
        assertThat(reader.count(ME)).isEqualTo(11)
        assertThat(first.map { it.memberId }).isEqualTo((11 downTo 2).map { ME + 100 + it })
        assertThat(second).isEqualTo(listOf(NoteBlockListReader.Block(ME + 101, Instant.parse("2026-03-01T00:00:01Z"))))
    }

    @Test
    fun `내가 건 차단과 받은 차단이 섞여 있을 때__작성자 차단 여부를 읽으면__내가 건 것만 번호순으로 준다`() {
        // given
        seedBlock(ME, THIRD)
        seedBlock(ME, YOU)
        seedBlock(THIRD, ME)

        // when
        val blocked = JdbcNoteBlockStatusReader(read, readTx).blockedAuthors(ME, listOf(THIRD, YOU, ME + 500))

        // then
        assertThat(blocked).isEqualTo(listOf(YOU, THIRD))
    }

    // ---- reading -----------------------------------------------------------------------------------------

    @Test
    fun `받은 쪽지와 보낸 쪽지와 만료된 쪽지가 있을 때__알림을 읽으면__만료 안 된 받은 쪽지만 최신순이고 안 읽은 수를 센다`() {
        // given
        val newest = UUID.randomUUID()
        val older = UUID.randomUUID()
        seedNote(newest, YOU, ME, "알림 새 쪽지", recipientBox = Mailbox(), createdAt = "2026-03-02T00:00:00Z")
        seedNote(older, YOU, ME, "알림 읽은 쪽지", recipientBox = Mailbox(readAt = "2026-03-02T01:00:00Z"), createdAt = "2026-03-01T00:00:00Z")
        seedNote(UUID.randomUUID(), YOU, ME, "알림 안 읽은 쪽지 둘", recipientBox = Mailbox(), createdAt = "2026-02-20T00:00:00Z")
        seedNote(UUID.randomUUID(), YOU, ME, "알림 만료", recipientBox = Mailbox(), createdAt = "2026-02-01T00:00:00Z", expiresAt = "2026-02-28T00:00:00Z")
        seedNote(UUID.randomUUID(), ME, YOU, "알림 보낸 쪽지", senderBox = Mailbox(), createdAt = "2026-03-03T00:00:00Z")
        val reader = JdbcNoteNotificationReader(read, readTx)
        val now = Instant.parse("2026-03-10T00:00:00Z")

        // when
        val rows = reader.read(ME, null, now)
        val after = reader.read(ME, newest, now)

        // then
        assertThat(rows.map { it.noteId }.take(2)).isEqualTo(listOf(newest, older))
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.senderId }).isEqualTo(listOf(YOU, YOU, YOU))
        assertThat(rows.map { it.readAt }.take(2)).isEqualTo(listOf(null, Instant.parse("2026-03-02T01:00:00Z")))
        assertThat(after.map { it.noteId }.first()).`as`("After the cursor").isEqualTo(older)
        assertThat(reader.read(ME, UUID.randomUUID(), now)).`as`("A cursor outside the inbox is empty").isEmpty()
        assertThat(reader.unread(ME, now)).isEqualTo(2)
    }

    @Test
    fun `받은 쪽지가 스물한 개를 넘을 때__알림을 읽으면__한 번에 스물한 개까지만 준다`() {
        // given
        for (index in 0 until 22) {
            seedNote(UUID.randomUUID(), YOU, ME, "알림 쪽지 $index", recipientBox = Mailbox(), createdAt = "2026-03-01T00:%02d:00Z".format(index))
        }

        // when
        val rows = JdbcNoteNotificationReader(read, readTx).read(ME, null, Instant.parse("2026-03-10T00:00:00Z"))

        // then
        assertThat(rows).hasSize(21)
    }

    @Test
    fun `받은 쪽지와 즐겨찾기와 보낸 쪽지가 있을 때__목록을 읽으면__상자와 즐겨찾기로 거르고 만료는 빼며 최신순으로 열 개씩 끊는다`() {
        // given
        val inboxA = UUID.randomUUID()
        val inboxB = UUID.randomUUID()
        val sent = UUID.randomUUID()
        seedNote(inboxA, YOU, ME, "목록 받은 A", recipientBox = Mailbox(favorite = true), createdAt = "2026-03-01T00:00:00Z")
        seedNote(inboxB, YOU, ME, "목록 받은 B", recipientBox = Mailbox(), createdAt = "2026-03-02T00:00:00Z")
        seedNote(sent, ME, YOU, "목록 보낸", senderBox = Mailbox(), createdAt = "2026-03-03T00:00:00Z")
        seedNote(UUID.randomUUID(), YOU, ME, "목록 만료", recipientBox = Mailbox(), createdAt = "2026-02-01T00:00:00Z", expiresAt = "2026-02-02T00:00:00Z")
        val reader = JdbcPrivateNoteListReader(read, readTx)
        val now = Instant.parse("2026-03-10T00:00:00Z")

        // when
        val inbox = reader.read(ME, "INBOX", false, 0, now)
        val favorites = reader.read(ME, "INBOX", true, 0, now)
        val outbox = reader.read(ME, "SENT", false, 0, now)

        // then
        assertThat(inbox.map { it.noteId }).isEqualTo(listOf(inboxB, inboxA))
        assertThat(inbox.map { it.favorite }).isEqualTo(listOf(false, true))
        assertThat(favorites.map { it.noteId }).isEqualTo(listOf(inboxA))
        assertThat(outbox.map { it.noteId }).isEqualTo(listOf(sent))
        assertThat(reader.count(ME, "INBOX", false, now)).isEqualTo(2)
        assertThat(reader.count(ME, "INBOX", true, now)).isEqualTo(1)
        assertThat(reader.count(ME, "SENT", false, now)).isEqualTo(1)
        assertThat(reader.read(ME, "INBOX", false, 1, now)).isEmpty()
    }

    @Test
    fun `내 사서함에 있는 쪽지와 남의 쪽지와 만료된 쪽지일 때__단건을 읽으면__내 쪽지만 모든 필드로 준다`() {
        // given
        val mine = UUID.randomUUID()
        seedNote(
            mine, YOU, ME, "단건 본문", recipientBox = Mailbox(readAt = "2026-03-02T01:00:00.5Z", favorite = true),
            createdAt = "2026-03-02T00:00:00.123456Z", context = "POST" to 77L,
        )
        val expired = UUID.randomUUID()
        seedNote(expired, YOU, ME, "단건 만료", recipientBox = Mailbox(), createdAt = "2026-02-01T00:00:00Z", expiresAt = "2026-02-02T00:00:00Z")
        val reader = JdbcPrivateNoteReader(read, readTx)
        val now = Instant.parse("2026-03-10T00:00:00Z")

        // when
        val found = reader.find(ME, mine, now)

        // then
        assertThat(found).isPresent
        with(found.get()) {
            assertThat(noteId).isEqualTo(mine)
            assertThat(senderId).isEqualTo(YOU)
            assertThat(recipientId).isEqualTo(ME)
            assertThat(body).isEqualTo("단건 본문")
            assertThat(contextType).isEqualTo("POST")
            assertThat(contextId).isEqualTo(77L)
            assertThat(createdAt).isEqualTo(Instant.parse("2026-03-02T00:00:00.123456Z"))
            assertThat(direction).isEqualTo("INBOX")
            assertThat(readAt).isEqualTo(Instant.parse("2026-03-02T01:00:00.5Z"))
            assertThat(favorite).isTrue()
        }
        assertThat(reader.find(THIRD, mine, now)).`as`("Another member").isEmpty
        assertThat(reader.find(ME, expired, now)).`as`("Expired").isEmpty
        assertThat(reader.find(ME, UUID.randomUUID(), now)).isEmpty
    }

    // ---- mutations ---------------------------------------------------------------------------------------

    @Test
    fun `읽지 않은 받은 쪽지일 때__읽음 처리하면__처음 읽은 시각만 남기고 커밋 뒤에 한 번 알린다`() {
        // given
        val note = UUID.randomUUID()
        seedNote(note, YOU, ME, "읽음 본문", senderBox = Mailbox(), recipientBox = Mailbox())
        val mutation = JdbcNoteReadMutation(write, writeTx, hints)
        val first = Instant.parse("2026-03-05T00:00:00Z")

        // when
        mutation.read(ME, note, {}, Supplier { first })
        mutation.read(ME, note, {}, Supplier { first.plusSeconds(3600) })

        // then
        assertThat(scalar("SELECT (read_at = ?::timestamptz)::text FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?", first.toString(), ME, note))
            .isEqualTo("true")
        assertThat(readAt(YOU, note)).`as`("The sender's sent copy is not touched").isNull()
        assertThat(resyncs).isEqualTo(listOf(ME, ME))
    }

    @Test
    fun `남의 쪽지이거나 만료되었거나 없을 때__읽음 즐겨찾기 삭제를 하면__없음 예외이고 아무것도 쓰지 않으며 알리지 않는다`() {
        // given
        val others = UUID.randomUUID()
        val expired = UUID.randomUUID()
        seedNote(others, YOU, THIRD, "남의 쪽지", senderBox = Mailbox(), recipientBox = Mailbox())
        seedNote(expired, YOU, ME, "만료 쪽지", recipientBox = Mailbox(), createdAt = "2026-02-01T00:00:00Z", expiresAt = "2026-02-02T00:00:00Z")
        val now = Supplier { Instant.parse("2026-03-10T00:00:00Z") }
        val before = ContractPostgres.snapshot()

        // when
        // then
        for (note in listOf(others, expired, UUID.randomUUID())) {
            assertThatThrownBy { JdbcNoteReadMutation(write, writeTx, hints).read(ME, note, {}, now) }.isInstanceOf(NoteReadMutation.MissingOwnedNote::class.java)
            assertThatThrownBy { JdbcNoteFavoriteMutation(write, writeTx, hints).set(ME, note, true, {}, now) }
                .isInstanceOf(NoteFavoriteMutation.MissingOwnedNote::class.java)
            assertThatThrownBy { JdbcNoteDeletion(write, writeTx, hints).delete(ME, note, {}, now) }.isInstanceOf(NoteDeletion.MissingOwnedNote::class.java)
        }
        assertThat(changedTables(before, ContractPostgres.snapshot())).`as`("Only the member row that the lock step inserts").isEqualTo(emptySet<String>())
        assertThat(resyncs).isEmpty()
    }

    @Test
    fun `내 사서함의 쪽지일 때__즐겨찾기를 켜고 끄면__내 사서함 행만 바뀐다`() {
        // given
        val note = UUID.randomUUID()
        seedNote(note, YOU, ME, "즐겨찾기 본문", senderBox = Mailbox(), recipientBox = Mailbox())
        val mutation = JdbcNoteFavoriteMutation(write, writeTx, hints)
        val now = Supplier { Instant.parse("2026-03-10T00:00:00Z").minusSeconds(86_400L * 20) }

        // when
        mutation.set(ME, note, true, {}, now)
        val on = favorite(ME, note)
        mutation.set(ME, note, false, {}, now)

        // then
        assertThat(on).isEqualTo("true")
        assertThat(favorite(ME, note)).isEqualTo("false")
        assertThat(favorite(YOU, note)).isEqualTo("false")
        assertThat(resyncs).isEqualTo(listOf(ME, ME))
    }

    @Test
    fun `상대 사서함이 남은 쪽지일 때__삭제하면__내 사서함만 지우고 마지막 사서함을 지우면 쪽지도 지운다`() {
        // given
        val note = UUID.randomUUID()
        seedNote(note, YOU, ME, "삭제 본문", senderBox = Mailbox(), recipientBox = Mailbox())
        val deletion = JdbcNoteDeletion(write, writeTx, hints)
        val now = Supplier { Instant.now() }

        // when
        deletion.delete(ME, note, {}, now)
        val afterFirst = noteExists(note) to mailboxExists(YOU, note)
        deletion.delete(YOU, note, {}, now)

        // then
        assertThat(afterFirst).isEqualTo(true to true)
        assertThat(mailboxExists(ME, note)).isFalse()
        assertThat(noteExists(note)).isFalse()
        assertThat(resyncs).isEqualTo(listOf(ME, YOU))
    }

    // ---- sending -----------------------------------------------------------------------------------------

    @Test
    fun `새 쪽지와 알림일 때__저장하면__쪽지 하나와 사서함 둘과 요청 하나를 쓰고 커밋 뒤에 수신자에게만 알린다`() {
        // given
        val published = ArrayList<Pair<Long, Notification>>()
        val store = JdbcNoteSendStore(write, writeTx) { member, notification -> published.add(member to notification) }
        val note = UUID.randomUUID()
        val request = UUID.randomUUID()
        val created = Instant.parse("2026-03-05T00:00:00.123456Z")
        val notification = Notification(note, "NOTE", ME, "나", created, false, "/notes/$note")

        // when
        store.inTransaction(Supplier {
            store.lockMembers(YOU, ME)
            store.insert(NewNote(note, ME, YOU, "보낸 본문", null, null, null, request, "h".repeat(64), created, created.plusSeconds(86_400)), notification)
            com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt(note)
        })

        // then
        assertThat(published).isEqualTo(listOf(YOU to notification))
        assertThat(scalar("SELECT sender_id || ':' || recipient_id || ':' || body FROM pawbridge_community.private_notes WHERE note_id = ?", note)).isEqualTo("$ME:$YOU:보낸 본문")
        assertThat(scalar("SELECT string_agg(member_id || ':' || direction || ':' || (read_at IS NOT NULL), ',' ORDER BY direction) FROM pawbridge_community.private_note_mailboxes WHERE note_id = ?", note))
            .`as`("The sender's copy is born read").isEqualTo("$YOU:INBOX:false,$ME:SENT:true")
        assertThat(store.request(ME, request).get().hash).isEqualTo("h".repeat(64))
        assertThat(store.request(ME, UUID.randomUUID())).isEmpty
    }

    @Test
    fun `같은 보낸이의 요청 키가 이미 있을 때__저장하면__중복 키 오류로 쪽지도 사서함도 남지 않고 알리지 않는다`() {
        // given
        val published = ArrayList<Long>()
        val store = JdbcNoteSendStore(write, writeTx) { member, _ -> published.add(member) }
        val request = UUID.randomUUID()
        val created = Instant.parse("2026-03-05T00:00:00Z")
        fun note(id: UUID) = NewNote(id, ME, YOU, "중복", null, null, null, request, "h".repeat(64), created, created.plusSeconds(60))
        val notification = Notification(null, "NOTE", ME, "나", created, false, null)
        val first = UUID.randomUUID()
        store.inTransaction(Supplier { store.insert(note(first), notification); com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt(first) })
        published.clear()

        // when
        // then
        assertThatThrownBy {
            store.inTransaction(Supplier { store.insert(note(UUID.randomUUID()), notification); com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt(null) })
        }.isInstanceOf(DuplicateKeyException::class.java)
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes")).isEqualTo("1")
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes")).isEqualTo("2")
        assertThat(published).isEmpty()
    }

    @Test
    fun `차단과 요청 기록과 답장 대상과 관련 글이 있을 때__발송 사전 조회를 하면__각 조건을 데이터베이스 결과로 판정한다`() {
        // given
        val store = JdbcNoteSendStore(write, writeTx) { _, _ -> }
        val note = UUID.randomUUID()
        val now = Instant.parse("2026-03-10T00:00:00Z")
        seedNote(note, YOU, ME, "답장 대상", senderBox = Mailbox(), recipientBox = Mailbox(), createdAt = "2026-03-09T00:00:00Z")
        seedNote(UUID.randomUUID(), YOU, ME, "만료 답장", recipientBox = Mailbox(), createdAt = "2026-02-01T00:00:00Z", expiresAt = "2026-02-02T00:00:00Z")
        seedBlock(THIRD, ME)
        seedUser(YOU, "작성자")
        seedPost(YOU, 9_920_900L, 9_920_901L)

        // when
        // then
        assertThat(store.blocked(ME, THIRD)).isTrue()
        assertThat(store.blocked(THIRD, ME)).isTrue()
        assertThat(store.blocked(ME, YOU)).isFalse()
        assertThat(store.reply(ME, note, now).get().direction).isEqualTo("INBOX")
        assertThat(store.reply(ME, note, now).get().senderId).isEqualTo(YOU)
        assertThat(store.reply(THIRD, note, now)).isEmpty
        assertThat(store.rate(YOU, Instant.parse("2026-03-01T00:00:00Z"))).isZero()
        assertThat(store.contextAuthor("POST", 9_920_900L, YOU)).isTrue()
        assertThat(store.contextAuthor("POST", 9_920_900L, ME)).`as`("Not the author").isFalse()
        assertThat(store.contextAuthor("POST", 9_920_901L, YOU)).`as`("Deleted post").isFalse()
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private fun favorite(member: Long, note: UUID): String? =
        scalar("SELECT favorite::text FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?", member, note)

    /** One active post (the first id) and one deleted post (the second id) of [author]; removed with the posts table restore. */
    private fun seedPost(author: Long, active: Long, deleted: Long) {
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_community.posts(post_id, author_id, title, content, board_type, image_urls, created_at, updated_at, deleted_at) " +
                "VALUES (?, ?, 't', 'c', 'COMMUNICATION', '[]', now(), now(), NULL), (?, ?, 't', 'c', 'COMMUNICATION', '[]', now(), now(), now())",
            active, author, deleted, author,
        )
        postIds.add(active)
        postIds.add(deleted)
    }

    private val postIds = ArrayList<Long>()

    @org.junit.jupiter.api.AfterEach
    fun removePosts() {
        for (id in postIds) {
            ContractPostgres.admin.update("DELETE FROM pawbridge_community.posts WHERE post_id = ?", id)
        }
        postIds.clear()
    }

    private companion object {
        const val ME = 9_920_001L
        const val YOU = 9_920_002L
        const val THIRD = 9_920_003L
    }
}
