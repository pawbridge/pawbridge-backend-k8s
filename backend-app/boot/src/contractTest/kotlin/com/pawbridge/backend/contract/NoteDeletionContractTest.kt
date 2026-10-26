package com.pawbridge.backend.contract

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `DELETE /api/v1/notes/{id}` on the real application, PostgreSQL and a real event stream: the caller's mailbox row
 * goes first, the note itself only when no mailbox is left, the foreign keys to it are cleared, the counterpart keeps
 * what is theirs, and a rolled-back deletion changes nothing and tells nobody.
 */
class NoteDeletionContractTest : NoteContractBase() {
    override val ids = 9_983_000L..9_983_999L

    private val noteA = UUID.fromString("f2800000-0000-4000-8000-000000000001")
    private val noteB = UUID.fromString("f2800000-0000-4000-8000-000000000002")
    private val noteC = UUID.fromString("f2800000-0000-4000-8000-000000000003")
    private val requestId = UUID.fromString("f2800000-0000-4000-8000-000000000010")

    /**
     * A: from the other member to the caller, kept by both. B: from the caller, kept only by the caller, with a
     * send request pointing at it. C: from the other member to the caller as a reply to B, kept only by the caller.
     */
    @BeforeEach
    fun seedNotes() {
        seedUser(CALLER, "F28caller$CALLER")
        seedUser(OTHER, "F28other$OTHER")
        seedMember(CALLER)
        seedMember(OTHER)
        seedNote(noteA, OTHER, CALLER, BODY, senderBox = Mailbox(readAt = READ_AT), recipientBox = Mailbox(favorite = true))
        seedNote(noteB, CALLER, OTHER, BODY, senderBox = Mailbox())
        seedNote(noteC, OTHER, CALLER, BODY, recipientBox = Mailbox(readAt = READ_AT, favorite = true), replyTo = noteB)
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_community.private_note_requests(sender_id, request_id, request_hash, note_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?::timestamptz)",
            CALLER, requestId, "f".repeat(64), noteB, STORED_TIME,
        )
    }

    private fun delete(note: UUID, token: String?) = call("DELETE", "$NOTES/$note", token)

    private fun requestNote(): String? {
        return scalar(
            "SELECT note_id::text FROM pawbridge_community.private_note_requests WHERE sender_id = ? AND request_id = ?",
            CALLER, requestId,
        )
    }

    private fun replyTo(note: UUID): String? {
        return scalar("SELECT reply_to::text FROM pawbridge_community.private_notes WHERE note_id = ?", note)
    }

    private fun counterpartView(): Map<String, Any?> = mapOf(
        "noteId" to noteA.toString(),
        "body" to BODY,
        "direction" to "SENT",
        "createdAt" to STORED_TIME,
        "readAt" to READ_AT,
        "favorite" to false,
        "counterpartId" to CALLER,
        "counterpartNickname" to "F28caller$CALLER",
        "canReply" to false,
        "contextHref" to null,
    )

    private fun feedItem(note: UUID, read: Boolean): Map<String, Any?> = mapOf(
        "noteId" to note.toString(), "kind" to "PRIVATE_NOTE", "actorId" to OTHER, "actorNickname" to "F28other$OTHER",
        "createdAt" to STORED_TIME, "read" to read, "href" to "/notes/$note",
    )

    private fun feed(unread: Int, vararg items: Map<String, Any?>): Map<String, Any?> =
        mapOf("content" to items.toList(), "nextCursor" to null, "unreadCount" to unread)

    @Test
    fun `상대 사서함이 남은 쪽지일 때__삭제하면__내 사서함 한 행만 지우고 쪽지와 상대 사서함은 남긴다`() {
        // given
        val caller = token(CALLER)
        val other = token(OTHER)
        val mine = openStream(caller)
        val theirs = openStream(other)
        mine.await("ready", 1)
        theirs.await("ready", 1)
        assertOk(get("$NOTES/notifications", caller), feed(1, feedItem(noteC, true), feedItem(noteA, false)))

        // when
        val (reply, changed) = observingChanges { delete(noteA, caller) }

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactly("pawbridge_community.private_note_mailboxes")
        assertThat(mailboxExists(CALLER, noteA)).isFalse()
        assertThat(mailboxExists(OTHER, noteA)).isTrue()
        assertThat(noteExists(noteA)).isTrue()
        mine.awaitExactly("resync", 1)
        theirs.awaitExactly("resync", 0)
        assertRejected(withoutWrites { get("$NOTES/$noteA", caller) }, 404, MISSING)
        assertOk(withoutWrites { get("$NOTES/$noteA", other) }, counterpartView())
        assertOk(withoutWrites { get("$NOTES/notifications", caller) }, feed(0, feedItem(noteC, true)))
    }

    @Test
    fun `마지막 사서함인 쪽지일 때__삭제하면__쪽지도 지우고 요청 기록과 답장 링크의 참조를 비운다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)
        assertThat(requestNote()).isEqualTo(noteB.toString())
        assertThat(replyTo(noteC)).isEqualTo(noteB.toString())

        // when
        val (reply, changed) = observingChanges { delete(noteB, caller) }

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactlyInAnyOrder(
            "pawbridge_community.private_note_mailboxes",
            "pawbridge_community.private_notes",
            "pawbridge_community.private_note_requests",
        )
        assertThat(noteExists(noteB)).isFalse()
        assertThat(requestNote()).isNull()
        assertThat(replyTo(noteC)).isNull()
        assertThat(noteExists(noteC)).isTrue()
        assertThat(mailboxExists(CALLER, noteC)).isTrue()
        mine.awaitExactly("resync", 1)
        assertRejected(withoutWrites { get("$NOTES/$noteB", caller) }, 404, MISSING)
    }

    @Test
    fun `이미 지운 쪽지일 때__다시 삭제하면__404이고 행도 재동기화도 없다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)
        delete(noteB, caller)
        mine.await("resync", 1)

        // when
        val reply = withoutWrites { delete(noteB, caller) }

        // then
        assertRejected(reply, 404, MISSING)
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `사서함 삭제 권한이 없을 때__삭제하면__저장소 503이고 재동기화도 없으며 복구하면 한 번 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("DELETE", "pawbridge_community.private_note_mailboxes") {
            withoutWrites { delete(noteA, caller) }
        }
        val recovered = delete(noteA, caller)

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(mailboxExists(CALLER, noteA)).isFalse()
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `쪽지 삭제 권한이 없을 때__마지막 사서함을 삭제하면__사서함 삭제까지 되돌리고 재동기화도 없다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("DELETE", "pawbridge_community.private_notes") {
            withoutWrites { delete(noteB, caller) }
        }
        val mailboxKept = mailboxExists(CALLER, noteB)
        val recovered = delete(noteB, caller)

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertThat(mailboxKept).`as`("The mailbox deletion rolled back with the failed note deletion").isTrue()
        assertEmptyOk(recovered)
        assertThat(noteExists(noteB)).isFalse()
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__삭제하면__회원 503이고 재동기화도 없으며 복구하면 한 번 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { delete(noteC, caller) }
        }
        val recovered = delete(noteC, caller)

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(noteExists(noteC)).isFalse()
        mine.awaitExactly("resync", 1)
        assertOk(withoutWrites { get("$NOTES/notifications", caller) }, feed(1, feedItem(noteA, false)))
    }

    @Test
    fun `토큰이 없거나 이웃 경로일 때__삭제를 호출하면__401과 403으로 거부하고 쪽지는 그대로다`() {
        // given
        val caller = token(CALLER)

        // when
        val missing = withoutWrites { delete(UUID.fromString("00000000-0000-0000-0000-000000000000"), null) }
        val extra = withoutWrites { call("DELETE", "$NOTES/$noteC/extra", caller) }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(extra, 403, "접근할 수 없습니다.")
        assertThat(noteExists(noteC)).isTrue()
    }

    private companion object {
        const val NOTES = "/api/v1/notes"
        const val CALLER = 9_983_001L
        const val OTHER = 9_983_002L
        const val BODY = "F28 owned synthetic note"
        const val READ_AT = "2026-10-18T15:00:30.654321Z"
        const val MISSING = "쪽지를 찾을 수 없습니다."
    }
}
