package com.pawbridge.backend.contract

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `PUT /api/v1/notes/{id}/read` on the real application, PostgreSQL and a real event stream: the first read stamps
 * one cell, the owner's stream is told to resynchronise only after the commit, another member's stream is not, and
 * the REST views show what the database holds.
 */
class NoteReadMutationContractTest : NoteContractBase() {
    override val ids = 9_981_000L..9_981_999L

    private val inbox = UUID.fromString("f2600000-0000-4000-8000-000000000001")
    private val failure = UUID.fromString("f2600000-0000-4000-8000-000000000002")
    private val sent = UUID.fromString("f2600000-0000-4000-8000-000000000003")

    @BeforeEach
    fun seedNotes() {
        seedUser(CALLER, "F26caller$CALLER")
        seedUser(OTHER, "F26other$OTHER")
        seedMember(CALLER)
        seedMember(OTHER)
        seedNote(inbox, OTHER, CALLER, BODY, recipientBox = Mailbox())
        seedNote(failure, OTHER, CALLER, BODY, recipientBox = Mailbox())
        seedNote(sent, CALLER, OTHER, BODY, senderBox = Mailbox())
    }

    private fun stampOf(note: UUID): Instant? {
        val stamp = ContractPostgres.admin.queryForObject(
            "SELECT read_at FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?",
            Timestamp::class.java, CALLER, note,
        )
        return stamp?.toInstant()
    }

    private fun put(note: UUID, token: String?) = call("PUT", "$NOTES/$note/read", token)

    private fun view(note: UUID, direction: String): Map<String, Any?> = mapOf(
        "noteId" to note.toString(),
        "body" to BODY,
        "direction" to direction,
        "createdAt" to STORED_TIME,
        "readAt" to stampOf(note)?.toString(),
        "favorite" to false,
        "counterpartId" to OTHER,
        "counterpartNickname" to "F26other$OTHER",
        "canReply" to (direction == "INBOX"),
        "contextHref" to null,
    )

    @Tag("timezone-sensitive")
    @Test
    fun `안 읽은 받은 쪽지일 때__읽음 처리하면__내 읽은 시각 한 칸만 바꾸고 내 스트림에만 재동기화를 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        val theirs = openStream(token(OTHER))
        mine.await("ready", 1)
        theirs.await("ready", 1)
        val started = Instant.now()

        // when
        val (reply, changed) = observingChanges { put(inbox, caller) }
        val finished = Instant.now()

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactly("pawbridge_community.private_note_mailboxes")
        assertThat(stampOf(inbox)).isBetween(started.minusSeconds(1), finished.plusSeconds(1))
        assertThat(stampOf(failure)).isNull()
        assertThat(stampOf(sent)).isNull()
        mine.awaitExactly("resync", 1)
        theirs.awaitExactly("resync", 0)
    }

    @Tag("timezone-sensitive")
    @Test
    fun `읽음 처리한 뒤__알림과 단건과 목록을 조회하면__데이터베이스의 읽은 시각을 그대로 보여준다`() {
        // given
        val caller = token(CALLER)
        put(inbox, caller)

        // when
        val notifications = withoutWrites { get("$NOTES/notifications", caller) }
        val detail = withoutWrites { get("$NOTES/$inbox", caller) }
        val inboxList = withoutWrites { get(NOTES, caller) }
        val sentList = withoutWrites { get("$NOTES?box=SENT", caller) }

        // then
        val actor = mapOf("actorId" to OTHER, "actorNickname" to "F26other$OTHER", "createdAt" to STORED_TIME, "kind" to "PRIVATE_NOTE")
        assertOk(
            notifications,
            mapOf(
                "content" to listOf(
                    actor + mapOf("noteId" to failure.toString(), "read" to false, "href" to "/notes/$failure"),
                    actor + mapOf("noteId" to inbox.toString(), "read" to true, "href" to "/notes/$inbox"),
                ),
                "nextCursor" to null,
                "unreadCount" to 1,
            ),
        )
        assertOk(detail, view(inbox, "INBOX"))
        assertOk(
            inboxList,
            mapOf(
                "content" to listOf(view(failure, "INBOX"), view(inbox, "INBOX")),
                "totalElements" to 2, "totalPages" to 1, "number" to 0, "size" to 10,
            ),
        )
        assertOk(
            sentList,
            mapOf("content" to listOf(view(sent, "SENT")), "totalElements" to 1, "totalPages" to 1, "number" to 0, "size" to 10),
        )
    }

    @Test
    fun `이미 읽은 쪽지일 때__다시 읽음 처리하면__처음 읽은 시각을 지키고 재동기화는 다시 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)
        put(inbox, caller)
        val first = stampOf(inbox)
        mine.await("resync", 1)

        // when
        val reply = withoutWrites { put(inbox, caller) }

        // then
        assertEmptyOk(reply)
        assertThat(stampOf(inbox)).isEqualTo(first)
        mine.awaitExactly("resync", 2)
    }

    @Test
    fun `내가 보낸 쪽지일 때__읽음 처리하면__200이고 행을 바꾸지 않지만 재동기화는 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val reply = withoutWrites { put(sent, caller) }

        // then
        assertEmptyOk(reply)
        assertThat(stampOf(sent)).isNull()
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `읽은 시각 쓰기 권한이 없을 때__읽음 처리하면__저장소 503이고 재동기화도 없으며 복구하면 한 번 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("UPDATE(read_at)", "pawbridge_community.private_note_mailboxes") {
            withoutWrites { put(failure, caller) }
        }
        val recovered = put(failure, caller)

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(stampOf(failure)).isNotNull()
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__읽음 처리하면__회원 503이고 읽지 않은 채 재동기화도 없으며 복구하면 한 번 보낸다`() {
        // given
        val caller = token(CALLER)
        val mine = openStream(caller)
        mine.await("ready", 1)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { put(inbox, caller) }
        }
        val unreadAfterFailure = stampOf(inbox)
        val recovered = put(inbox, caller)

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertThat(unreadAfterFailure).isNull()
        assertEmptyOk(recovered)
        mine.awaitExactly("resync", 1)
    }

    @Test
    fun `토큰이 없을 때__읽음 처리하면__401로 거부하고 읽지 않은 채로 둔다`() {
        // given / when
        val reply = withoutWrites { put(inbox, null) }

        // then
        assertSecurity(reply, 401, "인증 토큰이 필요합니다.")
        assertThat(stampOf(inbox)).isNull()
    }

    private companion object {
        const val NOTES = "/api/v1/notes"
        const val CALLER = 9_981_001L
        const val OTHER = 9_981_002L
        const val BODY = "F26 owned synthetic note"
    }
}
