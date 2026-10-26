package com.pawbridge.backend.contract

import java.time.Duration
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The three private-note reads on the real application and PostgreSQL: the notification feed with its cursor and
 * unread count, one note, and the mailbox pages. Each refusal is tied to the column whose privilege is missing, and no
 * read changes a row, least of all the read state.
 */
class NoteInboxReadContractTest : NoteContractBase() {
    override val ids = 9_975_000L..9_977_999L

    private fun uuid(base: Long, index: Long): UUID = UUID(0L, base + index)

    private fun item(
        note: UUID,
        body: String,
        direction: String,
        createdAt: String,
        readAt: String?,
        favorite: Boolean,
        counterpartId: Long?,
        counterpartNickname: String,
        canReply: Boolean,
        contextHref: String?,
    ): Map<String, Any?> = mapOf(
        "noteId" to note.toString(),
        "body" to body,
        "direction" to direction,
        "createdAt" to createdAt,
        "readAt" to readAt,
        "favorite" to favorite,
        "counterpartId" to counterpartId,
        "counterpartNickname" to counterpartNickname,
        "canReply" to canReply,
        "contextHref" to contextHref,
    )

    // ---- GET /api/v1/notes/notifications ------------------------------------------------------------------

    private val feed = (1L..23L).map { uuid(210_000, it) }
    private val foreign = uuid(210_000, 101)
    private val sentNote = uuid(210_000, 102)
    private val expired = uuid(210_000, 103)

    private fun senderOf(index: Int): Long? = listOf<Long?>(FEED_NORMAL, FEED_RAW, FEED_PENDING, FEED_MISSING, null)[(index - 1) % 5]

    private fun seedFeed() {
        seedUser(FEED_CALLER, "F21caller")
        seedUser(FEED_NORMAL, "F21normal")
        seedUser(FEED_RAW, "  ")
        seedUser(FEED_PENDING, "F21pending")
        seedUser(FEED_OTHER, "F21other")
        for (member in listOf(FEED_CALLER, FEED_NORMAL, FEED_RAW, FEED_PENDING, FEED_MISSING, FEED_OTHER)) seedMember(member)
        seedPendingDeletion(FEED_PENDING)
        feed.forEachIndexed { position, note ->
            val index = position + 1
            seedNote(
                note, senderOf(index), FEED_CALLER, FEED_BODY,
                recipientBox = Mailbox(readAt = if (index % 3 == 0) STORED_TIME else null),
            )
        }
        seedNote(foreign, FEED_NORMAL, FEED_OTHER, FEED_BODY, recipientBox = Mailbox())
        seedNote(sentNote, FEED_CALLER, FEED_OTHER, FEED_BODY, senderBox = Mailbox())
        seedNote(expired, FEED_NORMAL, FEED_CALLER, FEED_BODY, recipientBox = Mailbox(), expiresAt = "1970-01-01T00:00:00Z")
    }

    private fun expectedFeed(indexes: List<Int>, nextCursor: UUID?): Map<String, Any?> {
        val content = indexes.map { index ->
            val sender = senderOf(index)
            val active = sender == FEED_NORMAL || sender == FEED_RAW
            mapOf(
                "noteId" to feed[index - 1].toString(),
                "kind" to "PRIVATE_NOTE",
                "actorId" to if (active) sender else null,
                "actorNickname" to if (!active) WITHDRAWN else if (sender == FEED_RAW) "  " else "F21normal",
                "createdAt" to STORED_TIME,
                "read" to (index % 3 == 0),
                "href" to "/notes/${feed[index - 1]}",
            )
        }
        return mapOf("content" to content, "nextCursor" to nextCursor?.toString(), "unreadCount" to 16)
    }

    @Tag("timezone-sensitive")
    @Test
    fun `스물세 개가 쌓였을 때__알림을 조회하면__스무 개와 다음 커서와 안 읽음 수를 준다`() {
        // given
        seedFeed()
        val caller = token(FEED_CALLER)

        // when
        val reply = withoutWrites { get(FEED, caller) }

        // then
        assertOk(reply, expectedFeed((23 downTo 4).toList(), feed[3]))
    }

    @Test
    fun `다음 커서를 주었을 때__알림을 조회하면__남은 세 개만 주고 커서를 끝낸다`() {
        // given
        seedFeed()
        val caller = token(FEED_CALLER)

        // when
        val reply = withoutWrites { get("$FEED?cursor=${feed[3]}", caller) }

        // then
        assertOk(reply, expectedFeed(listOf(3, 2, 1), null))
    }

    @Test
    fun `내 쪽지함에 없는 커서일 때__알림을 조회하면__빈 목록과 전체 안 읽음 수를 준다`() {
        // given
        seedFeed()
        val caller = token(FEED_CALLER)

        // when
        val reply = withoutWrites { get("$FEED?cursor=$foreign", caller) }

        // then
        assertOk(reply, expectedFeed(emptyList(), null))
    }

    @Test
    fun `본문을 읽을 수 없을 때__알림을 조회하면__본문을 읽지 않으므로 그대로 준다`() {
        // given
        seedFeed()
        val caller = token(FEED_CALLER)

        // when
        val reply = ContractPostgres.withRevoked("SELECT(body)", "pawbridge_community.private_notes") {
            withoutWrites { get(FEED, caller) }
        }

        // then
        assertOk(reply, expectedFeed((23 downTo 4).toList(), feed[3]))
    }

    @Test
    fun `만료 시각을 읽을 수 없을 때__알림을 조회하면__저장소 503이고 복구하면 다시 읽는다`() {
        // given
        seedFeed()
        val caller = token(FEED_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(expires_at)", "pawbridge_community.private_notes") {
            withoutWrites { get(FEED, caller) }
        }
        val recovered = withoutWrites { get(FEED, caller) }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertOk(recovered, expectedFeed((23 downTo 4).toList(), feed[3]))
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__알림을 조회하면__회원 503이고 복구하면 다시 읽는다`() {
        // given
        seedFeed()
        val caller = token(FEED_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { get(FEED, caller) }
        }
        val recovered = withoutWrites { get(FEED, caller) }

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertOk(recovered, expectedFeed((23 downTo 4).toList(), feed[3]))
    }

    @Test
    fun `토큰이 없거나 쓰기 메서드일 때__알림을 호출하면__401과 403으로 거부한다`() {
        // given
        val caller = token(FEED_CALLER)

        // when
        val missing = withoutWrites { get(FEED, null) }
        val write = withoutWrites { call("POST", FEED, caller, "{}") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(write, 403, "접근할 수 없습니다.")
    }

    // ---- GET /api/v1/notes/{id} ---------------------------------------------------------------------------

    private val detailInbox = uuid(220_000, 1)
    private val detailSent = uuid(220_000, 2)
    private val detailNullSender = uuid(220_000, 3)
    private val detailForeign = uuid(220_000, 4)

    private fun seedDetail() {
        seedUser(DETAIL_CALLER, "F22caller")
        seedUser(DETAIL_OTHER, DETAIL_NICKNAME)
        for (member in listOf(DETAIL_CALLER, DETAIL_OTHER, DETAIL_NULL)) seedMember(member)
        seedNote(
            detailInbox, DETAIL_OTHER, DETAIL_CALLER, DETAIL_BODY,
            recipientBox = Mailbox(favorite = true), createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME,
            context = "POST" to Long.MAX_VALUE,
        )
        seedNote(
            detailSent, DETAIL_CALLER, DETAIL_OTHER, DETAIL_BODY,
            senderBox = Mailbox(readAt = DETAIL_READ_AT), createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME,
            context = "REPORT" to 8L,
        )
        seedNote(
            detailNullSender, null, DETAIL_CALLER, DETAIL_BODY,
            recipientBox = Mailbox(), createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME,
        )
        seedNote(
            detailForeign, DETAIL_CALLER, DETAIL_OTHER, DETAIL_BODY,
            recipientBox = Mailbox(), createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME,
        )
    }

    private fun inboxView() = item(
        detailInbox, DETAIL_BODY, "INBOX", DETAIL_CREATED, null, true, DETAIL_OTHER, DETAIL_NICKNAME, true,
        "/community/9223372036854775807",
    )

    @Tag("timezone-sensitive")
    @Test
    fun `받은 쪽지일 때__단건을 조회하면__원본 본문과 별명과 맥락 링크를 주고 읽음 상태는 그대로다`() {
        // given
        seedDetail()
        val caller = token(DETAIL_CALLER)

        // when
        val reply = withoutWrites { get("$NOTES/$detailInbox", caller) }

        // then
        assertOk(reply, inboxView())
        assertThat(readAt(DETAIL_CALLER, detailInbox)).isNull()
    }

    @Tag("timezone-sensitive")
    @Test
    fun `보낸 쪽지일 때__단건을 조회하면__읽은 시각과 답장 불가를 준다`() {
        // given
        seedDetail()
        val caller = token(DETAIL_CALLER)

        // when
        val reply = withoutWrites { get("$NOTES/$detailSent", caller) }

        // then
        assertOk(
            reply,
            item(detailSent, DETAIL_BODY, "SENT", DETAIL_CREATED, DETAIL_READ_AT, false, DETAIL_OTHER, DETAIL_NICKNAME, false, "/reports/8"),
        )
    }

    @Test
    fun `발신자가 탈퇴했을 때__단건을 조회하면__탈퇴한 회원으로 보여주고 답장은 막는다`() {
        // given
        seedDetail()
        val caller = token(DETAIL_CALLER)

        // when
        val reply = withoutWrites { get("$NOTES/$detailNullSender", caller) }

        // then
        assertOk(reply, item(detailNullSender, DETAIL_BODY, "INBOX", DETAIL_CREATED, null, false, null, WITHDRAWN, false, null))
    }

    @Test
    fun `남의 쪽지일 때__단건을 조회하면__없는 쪽지처럼 404로 거부한다`() {
        // given
        seedDetail()
        val caller = token(DETAIL_CALLER)

        // when
        val reply = withoutWrites { get("$NOTES/$detailForeign", caller) }

        // then
        assertRejected(reply, 404, "쪽지를 찾을 수 없습니다.")
    }

    @Test
    fun `본문을 읽을 수 없을 때__단건을 조회하면__저장소 503이고 복구하면 다시 읽으며 읽음 상태는 그대로다`() {
        // given
        seedDetail()
        val caller = token(DETAIL_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(body)", "pawbridge_community.private_notes") {
            withoutWrites { get("$NOTES/$detailInbox", caller) }
        }
        val recovered = withoutWrites { get("$NOTES/$detailInbox", caller) }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertOk(recovered, inboxView())
        assertThat(readAt(DETAIL_CALLER, detailInbox)).isNull()
    }

    @Test
    fun `토큰이 없거나 스트림과 쓰기 이웃 경로일 때__단건을 호출하면__401과 403으로 거부한다`() {
        // given
        seedDetail()
        val caller = token(DETAIL_CALLER)

        // when
        val missing = withoutWrites { get("$NOTES/$detailInbox", null) }
        val stream = withoutWrites { get("$NOTES/stream/extra", caller) }
        val write = withoutWrites { call("PUT", "$NOTES/$detailInbox/read/extra", caller) }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(stream, 403, "접근할 수 없습니다.")
        assertSecurity(write, 403, "접근할 수 없습니다.")
        assertThat(readAt(DETAIL_CALLER, detailInbox)).isNull()
    }

    // ---- GET /api/v1/notes ---------------------------------------------------------------------------------

    private val boxInbox = (1L..12L).map { uuid(230_000, it) }
    private val boxSent = uuid(230_000, 13)
    private val boxForeign = uuid(230_000, 14)
    private val boxExpired = uuid(230_000, 15)

    private fun seedBox() {
        seedUser(BOX_CALLER, "F23caller")
        seedUser(BOX_OTHER, BOX_NICKNAME)
        for (member in listOf(BOX_CALLER, BOX_OTHER, BOX_NULL)) seedMember(member)
        boxInbox.forEachIndexed { position, note ->
            seedNote(
                note, if (position == 0) null else BOX_OTHER, BOX_CALLER, BOX_BODY,
                recipientBox = Mailbox(
                    readAt = if (position == 11) DETAIL_READ_AT else null,
                    favorite = (position + 1) % 2 == 0,
                ),
                createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME, context = "POST" to 23L,
            )
        }
        seedNote(
            boxSent, BOX_CALLER, BOX_OTHER, BOX_BODY, senderBox = Mailbox(),
            createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME, context = "REPORT" to 8L,
        )
        seedNote(
            boxForeign, BOX_CALLER, BOX_OTHER, BOX_BODY, recipientBox = Mailbox(),
            createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME,
        )
        seedNote(
            boxExpired, BOX_OTHER, BOX_CALLER, BOX_BODY, recipientBox = Mailbox(),
            createdAt = DETAIL_CREATED, mailboxCreatedAt = DETAIL_MAILBOX_TIME, expiresAt = "2000-01-01T00:00:00Z",
        )
    }

    private fun boxView(note: UUID): Map<String, Any?> {
        if (note == boxSent) {
            return item(note, BOX_BODY, "SENT", DETAIL_CREATED, null, false, BOX_OTHER, BOX_NICKNAME, false, "/reports/8")
        }
        val position = boxInbox.indexOf(note)
        return item(
            note, BOX_BODY, "INBOX", DETAIL_CREATED, if (position == 11) DETAIL_READ_AT else null,
            (position + 1) % 2 == 0, if (position == 0) null else BOX_OTHER,
            if (position == 0) WITHDRAWN else BOX_NICKNAME, position != 0, "/community/23",
        )
    }

    private fun boxPage(notes: List<UUID>, page: Int, size: Int = 10): Map<String, Any?> = mapOf(
        "content" to notes.drop(page * size).take(size).map { boxView(it) },
        "totalElements" to notes.size,
        "totalPages" to (notes.size + size - 1) / size,
        "number" to page,
        "size" to size,
    )

    @Tag("timezone-sensitive")
    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(strings = ["", "?page=1", "?box=SENT", "?favorites=true", "?page=10000"])
    fun `받은 쪽지와 보낸 쪽지가 섞여 있을 때__쪽지함을 조회하면__상자와 즐겨찾기와 페이지대로 원본 순서로 준다`(query: String) {
        // given
        seedBox()
        val caller = token(BOX_CALLER)
        val newestFirst = boxInbox.reversed()
        val expected = when (query) {
            "" -> boxPage(newestFirst, 0)
            "?page=1" -> boxPage(newestFirst, 1)
            "?box=SENT" -> boxPage(listOf(boxSent), 0)
            "?favorites=true" -> boxPage(newestFirst.filter { (boxInbox.indexOf(it) + 1) % 2 == 0 }, 0)
            else -> boxPage(newestFirst, 10000)
        }

        // when
        val reply = withoutWrites { get("$NOTES$query", caller) }

        // then
        assertOk(reply, expected)
    }

    @Test
    fun `범위를 벗어난 페이지일 때__쪽지함을 조회하면__페이지 범위 400으로 거부한다`() {
        // given
        seedBox()
        val caller = token(BOX_CALLER)

        // when
        val reply = withoutWrites { get("$NOTES?page=-1&box=invalid", caller) }

        // then
        assertRejected(reply, 400, "페이지 범위를 확인해 주세요.")
    }

    @Test
    fun `쪽지함 시각을 읽을 수 없을 때__쪽지함을 조회하면__저장소 503이고 복구하면 다시 읽는다`() {
        // given
        seedBox()
        val caller = token(BOX_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(created_at)", "pawbridge_community.private_note_mailboxes") {
            withoutWrites { get(NOTES, caller) }
        }
        val recovered = withoutWrites { get(NOTES, caller) }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertOk(recovered, boxPage(boxInbox.reversed(), 0))
    }

    @Test
    fun `토큰이 없거나 만료되었거나 이웃 경로일 때__쪽지함을 호출하면__401과 403으로 거부한다`() {
        // given
        seedBox()
        val caller = token(BOX_CALLER)
        val expiredToken = token(BOX_CALLER, lifetime = Duration.ofSeconds(-60))

        // when
        val missing = withoutWrites { get("$NOTES?page=invalid", null) }
        val old = withoutWrites { get(NOTES, expiredToken) }
        val stream = withoutWrites { get("$NOTES/stream/extra", caller) }
        val unexposed = withoutWrites { call("POST", "$NOTES/unexposed/send", caller, "{}") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(old, 401, "유효하지 않거나 만료된 토큰입니다.")
        assertSecurity(stream, 403, "접근할 수 없습니다.")
        assertSecurity(unexposed, 403, "접근할 수 없습니다.")
    }

    @Test
    fun `쪽지함과 단건이 같은 쪽지일 때__둘 다 조회하면__같은 보기를 준다`() {
        // given
        seedBox()
        val caller = token(BOX_CALLER)
        val last = boxInbox.last()

        // when
        val detail = withoutWrites { get("$NOTES/$last", caller) }

        // then
        assertOk(detail, boxView(last))
    }

    private companion object {
        const val FEED = "/api/v1/notes/notifications"
        const val NOTES = "/api/v1/notes"

        const val FEED_CALLER = 9_975_001L
        const val FEED_NORMAL = 9_975_002L
        const val FEED_RAW = 9_975_003L
        const val FEED_PENDING = 9_975_004L
        const val FEED_MISSING = 9_975_005L
        const val FEED_OTHER = 9_975_006L
        const val FEED_BODY = "F21 synthetic private body"

        const val DETAIL_CALLER = 9_976_001L
        const val DETAIL_OTHER = 9_976_002L
        const val DETAIL_NULL = 9_976_003L
        const val DETAIL_NICKNAME = " 原文 F22nickname "
        const val DETAIL_BODY = " F22 synthetic private body\nraw line "
        const val DETAIL_CREATED = "2031-01-01T00:00:00.123456Z"
        const val DETAIL_READ_AT = "2031-01-01T00:00:01.654321Z"
        const val DETAIL_MAILBOX_TIME = "2031-01-02T00:00:00.000001Z"

        const val BOX_CALLER = 9_977_001L
        const val BOX_OTHER = 9_977_002L
        const val BOX_NULL = 9_977_003L
        const val BOX_NICKNAME = " 原文 F23nickname "
        const val BOX_BODY = " F23 synthetic private body\nraw line "
    }
}
