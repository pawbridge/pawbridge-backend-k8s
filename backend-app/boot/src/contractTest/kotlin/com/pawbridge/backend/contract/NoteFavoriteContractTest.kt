package com.pawbridge.backend.contract

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `PUT /api/v1/notes/{id}/favorite` on the real application, PostgreSQL and a real event stream: only the caller's
 * own favorite cell changes, every accepted request tells the caller's stream to resynchronise (also when nothing
 * changed), a rolled-back request tells nobody, and the filters show what the database holds.
 */
class NoteFavoriteContractTest : NoteContractBase() {
    override val ids = 9_982_000L..9_982_999L

    private val inbox = UUID.fromString("f2700000-0000-4000-8000-000000000001")
    private val readInbox = UUID.fromString("f2700000-0000-4000-8000-000000000002")
    private val sent = UUID.fromString("f2700000-0000-4000-8000-000000000003")

    @BeforeEach
    fun seedNotes() {
        seedUser(CALLER, "F27caller$CALLER")
        seedUser(OTHER, "F27other$OTHER")
        seedMember(CALLER)
        seedMember(OTHER)
        seedNote(inbox, OTHER, CALLER, BODY, recipientBox = Mailbox())
        seedNote(readInbox, OTHER, CALLER, BODY, recipientBox = Mailbox(readAt = READ_AT))
        seedNote(sent, CALLER, OTHER, BODY, senderBox = Mailbox(readAt = READ_AT))
    }

    private fun favoriteOf(note: UUID): Boolean {
        return ContractPostgres.admin.queryForObject(
            "SELECT favorite FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?",
            Boolean::class.javaObjectType, CALLER, note,
        )!!
    }

    private fun put(note: UUID, token: String?, favorite: Boolean) =
        call("PUT", "$NOTES/$note/favorite", token, """{"favorite":$favorite}""")

    private fun view(note: UUID, direction: String, readAt: String?): Map<String, Any?> = mapOf(
        "noteId" to note.toString(),
        "body" to BODY,
        "direction" to direction,
        "createdAt" to STORED_TIME,
        "readAt" to readAt,
        "favorite" to favoriteOf(note),
        "counterpartId" to OTHER,
        "counterpartNickname" to "F27other$OTHER",
        "canReply" to (direction == "INBOX"),
        "contextHref" to null,
    )

    private fun page(vararg views: Map<String, Any?>): Map<String, Any?> = mapOf(
        "content" to views.toList(),
        "totalElements" to views.size,
        "totalPages" to if (views.isEmpty()) 0 else 1,
        "number" to 0,
        "size" to 10,
    )

    @Test
    fun `지정하지 않은 받은 쪽지일 때__즐겨찾기로 지정하면__내 즐겨찾기 한 칸만 바꾸고 내 스트림에만 재동기화를 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        val theirs = openStream(token(OTHER))
        mine.await("ready", 1)
        theirs.await("ready", 1)

        // when
        val (reply, changed) = observingChanges { put(inbox, caller, true) }

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactly("pawbridge_community.private_note_mailboxes")
        assertThat(favoriteOf(inbox)).isTrue()
        assertThat(favoriteOf(readInbox)).isFalse()
        assertThat(favoriteOf(sent)).isFalse()
        mine.awaitExactly("resync", 1)
        theirs.awaitExactly("resync", 0)
    }

    @Test
    fun `지정한 뒤__단건과 즐겨찾기 목록과 알림을 조회하면__지정 상태만 바뀌고 읽은 시각은 그대로다`() {
        // given
        val caller = token(CALLER)
        put(inbox, caller, true)

        // when
        val detail = withoutWrites { get("$NOTES/$inbox", caller) }
        val favorites = withoutWrites { get("$NOTES?favorites=true", caller) }
        val notifications = withoutWrites { get("$NOTES/notifications", caller) }

        // then
        assertOk(detail, view(inbox, "INBOX", null))
        assertOk(favorites, page(view(inbox, "INBOX", null)))
        assertThat(notifications.json.get("data").get("unreadCount").asInt()).isEqualTo(1)
        assertThat(favoriteOf(readInbox)).isFalse()
    }

    @Test
    fun `이미 지정한 쪽지일 때__다시 지정하면__행은 그대로이고 재동기화는 다시 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)
        put(inbox, caller, true)
        mine.await("resync", 1)

        // when
        val reply = withoutWrites { put(inbox, caller, true) }

        // then
        assertEmptyOk(reply)
        assertThat(favoriteOf(inbox)).isTrue()
        mine.awaitExactly("resync", 2)
    }

    @Test
    fun `지정한 쪽지일 때__해제하면__즐겨찾기 목록에서 빠지고 재동기화를 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)
        put(inbox, caller, true)
        mine.await("resync", 1)

        // when
        val (reply, changed) = observingChanges { put(inbox, caller, false) }
        val favorites = withoutWrites { get("$NOTES?favorites=true", caller) }

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactly("pawbridge_community.private_note_mailboxes")
        assertOk(favorites, page())
        mine.awaitExactly("resync", 2)
    }

    @Test
    fun `내가 보낸 쪽지일 때__지정하면__보낸 상자 즐겨찾기에 나오고 읽은 시각은 그대로다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val reply = put(sent, caller, true)
        val favorites = withoutWrites { get("$NOTES?box=SENT&favorites=true", caller) }
        val inboxFavorites = withoutWrites { get("$NOTES?favorites=true", caller) }

        // then
        assertEmptyOk(reply)
        assertOk(favorites, page(view(sent, "SENT", READ_AT)))
        assertOk(inboxFavorites, page())
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `즐겨찾기 쓰기 권한이 없을 때__지정하면__저장소 503이고 재동기화도 없으며 복구하면 한 번 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("UPDATE(favorite)", "pawbridge_community.private_note_mailboxes") {
            withoutWrites { put(sent, caller, true) }
        }
        val recovered = put(sent, caller, true)

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(favoriteOf(sent)).isTrue()
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__지정하면__회원 503이고 재동기화도 없으며 복구하면 한 번 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { put(inbox, caller, true) }
        }
        val recovered = put(inbox, caller, true)

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(favoriteOf(inbox)).isTrue()
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `토큰이 없거나 이웃 경로일 때__호출하면__본문이 깨져도 401과 403으로 거부한다`() {
        // given
        val caller = token(CALLER)

        // when
        val missing = withoutWrites { call("PUT", "$NOTES/not-a-uuid/favorite", null, "{broken") }
        val extra = withoutWrites { call("PUT", "$NOTES/$inbox/favorite/extra", caller, """{"favorite":true}""") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(extra, 403, "접근할 수 없습니다.")
        assertThat(favoriteOf(inbox)).isFalse()
    }

    private companion object {
        const val NOTES = "/api/v1/notes"
        const val CALLER = 9_982_001L
        const val OTHER = 9_982_002L
        const val BODY = "F27 owned synthetic note"
        const val READ_AT = "2026-10-18T15:00:30.654321Z"
    }
}
