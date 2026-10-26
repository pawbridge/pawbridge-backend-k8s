package com.pawbridge.backend.contract

import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * `POST /api/v1/notes` on the real application, PostgreSQL and a real event stream: one send writes one note, two
 * mailbox rows and one request row in a single transaction; the recipient's stream is told only after the commit and
 * never for a rolled-back send; the same request id replays the same note and a different body under it conflicts.
 */
class NoteSendContractTest : NoteContractBase() {
    override val ids = 9_984_000L..9_984_999L

    private val first = UUID.fromString("f2900000-0000-4000-8000-000000000101")
    private val second = UUID.fromString("f2900000-0000-4000-8000-000000000102")

    @BeforeEach
    fun seedMembers() {
        seedUser(SENDER, SENDER_NICKNAME)
        seedUser(RECIPIENT, RECIPIENT_NICKNAME)
        seedMember(SENDER)
        seedMember(RECIPIENT)
        secrets.add(RAW_BODY)
        secrets.add(BODY)
        secrets.add(requestHash())
    }

    private fun requestHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$RECIPIENT\nnull\nnull\nnull\n$BODY".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun send(key: UUID, token: String?, body: String = RAW_BODY) = call(
        "POST", NOTES, token,
        MAPPER.writeValueAsString(mapOf("recipientId" to RECIPIENT, "body" to body, "requestId" to key.toString())),
    )

    private fun instantOf(row: Map<String, Any?>, column: String): Instant = (row[column] as Timestamp).toInstant()

    private fun noteRows(): List<Map<String, Any?>> = ContractPostgres.admin.queryForList(
        "SELECT * FROM pawbridge_community.private_notes WHERE sender_id = ?", SENDER,
    )

    private fun mailboxRows(note: String): List<Map<String, Any?>> = ContractPostgres.admin.queryForList(
        "SELECT * FROM pawbridge_community.private_note_mailboxes WHERE note_id = ?::uuid ORDER BY member_id", note,
    )

    private fun requestRows(): List<Map<String, Any?>> = ContractPostgres.admin.queryForList(
        "SELECT * FROM pawbridge_community.private_note_requests WHERE sender_id = ?", SENDER,
    )

    private fun noteIdOf(reply: Reply): String {
        assertThat(reply.json.get("data").fieldNames().asSequence().toList()).containsExactly("noteId")
        return reply.json.get("data").get("noteId").asText()
    }

    private fun closeTo(actual: Instant, expected: Instant) {
        assertThat(Duration.between(actual, expected).abs()).isLessThanOrEqualTo(Duration.ofNanos(2_000))
    }

    /** The committed rows of one accepted send, checked against the original specification. */
    private fun assertCommitted(key: UUID, noteId: String, started: Instant, finished: Instant): Instant {
        val notes = noteRows()
        assertThat(notes).hasSize(1)
        val note = notes.single()
        assertThat(note["note_id"].toString()).isEqualTo(noteId)
        assertThat(note["sender_id"]).isEqualTo(SENDER)
        assertThat(note["recipient_id"]).isEqualTo(RECIPIENT)
        assertThat(note["body"]).`as`("The stored body is trimmed").isEqualTo(BODY)
        assertThat(listOf(note["reply_to"], note["context_type"], note["context_id"])).containsOnlyNulls()
        val created = instantOf(note, "created_at")
        assertThat(created).isBetween(started.minusMillis(5), finished.plusMillis(5))
        closeTo(instantOf(note, "expires_at"), created.atZone(ZoneOffset.UTC).plusYears(1).toInstant())

        val requests = requestRows()
        assertThat(requests).hasSize(1)
        val request = requests.single()
        assertThat(request["request_id"].toString()).isEqualTo(key.toString())
        assertThat(request["request_hash"]).isEqualTo(requestHash())
        assertThat(request["note_id"].toString()).isEqualTo(noteId)
        closeTo(instantOf(request, "created_at"), created)

        val boxes = mailboxRows(noteId)
        assertThat(boxes.map { it["member_id"] }).containsExactly(SENDER, RECIPIENT)
        val (sent, inbox) = boxes
        assertThat(sent["direction"]).isEqualTo("SENT")
        closeTo(instantOf(sent, "read_at"), created)
        assertThat(inbox["direction"]).isEqualTo("INBOX")
        assertThat(inbox["read_at"]).isNull()
        for (box in boxes) {
            assertThat(box["favorite"]).isEqualTo(false)
            closeTo(instantOf(box, "created_at"), created)
        }
        return created
    }

    private fun assertNotification(payload: com.fasterxml.jackson.databind.JsonNode, noteId: String, created: Instant) {
        assertThat(payload.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("noteId", "kind", "actorId", "actorNickname", "createdAt", "read", "href")
        assertThat(payload.get("noteId").asText()).isEqualTo(noteId)
        assertThat(payload.get("kind").asText()).isEqualTo("PRIVATE_NOTE")
        assertThat(payload.get("actorId").isIntegralNumber).isTrue()
        assertThat(payload.get("actorId").asLong()).isEqualTo(SENDER)
        assertThat(payload.get("actorNickname").asText()).isEqualTo(SENDER_NICKNAME)
        assertThat(payload.get("read").isBoolean).isTrue()
        assertThat(payload.get("read").asBoolean()).isFalse()
        assertThat(payload.get("href").asText()).isEqualTo("/notes/$noteId")
        assertThat(payload.get("createdAt").asText()).endsWith("Z")
        closeTo(Instant.parse(payload.get("createdAt").asText()), created)
    }

    @Tag("timezone-sensitive")
    @Test
    fun `새 전송일 때__보내면__쪽지 한 건과 사서함 두 행과 요청 한 행만 쓰고 받는 사람 스트림에만 알린다`() {
        // given
        val sender = token(SENDER)
        val outgoing = openStream(sender)
        val incoming = openStream(token(RECIPIENT))
        outgoing.await("ready", 1)
        incoming.await("ready", 1)
        val started = Instant.now()

        // when
        val (reply, changed) = observingChanges { send(first, sender) }
        val finished = Instant.now()

        // then
        assertThat(reply.status).`as`(reply.text).isEqualTo(200)
        assertThat(reply.header("Cache-Control")).isEqualTo("no-store")
        assertThat(reply.header("Set-Cookie")).isNull()
        assertThat(reply.json.get("code").asInt()).isEqualTo(200)
        assertThat(reply.json.get("message").asText()).isEqualTo(SUCCESS)
        val noteId = noteIdOf(reply)
        assertThat(changed).containsExactlyInAnyOrder(
            "pawbridge_community.private_notes",
            "pawbridge_community.private_note_mailboxes",
            "pawbridge_community.private_note_requests",
        )
        val created = assertCommitted(first, noteId, started, finished)
        incoming.awaitExactly("note", 1)
        assertNotification(incoming.payloads("note").single(), noteId, created)
        outgoing.awaitExactly("note", 0)
        outgoing.awaitExactly("resync", 0)
        incoming.awaitExactly("resync", 0)
    }

    @Tag("timezone-sensitive")
    @Test
    fun `보낸 뒤__양쪽이 쪽지함과 단건과 알림을 조회하면__커밋된 쪽지를 같은 시각으로 보여준다`() {
        // given
        val sender = token(SENDER)
        val recipient = token(RECIPIENT)
        val noteId = noteIdOf(send(first, sender))
        val created = instantOf(noteRows().single(), "created_at").toString()

        // when
        val sentList = withoutWrites { get("$NOTES?box=SENT", sender) }
        val inboxList = withoutWrites { get(NOTES, recipient) }
        val detail = withoutWrites { get("$NOTES/$noteId", recipient) }
        val notifications = withoutWrites { get("$NOTES/notifications", recipient) }

        // then
        fun view(direction: String, counterpart: Long, nickname: String): Map<String, Any?> = mapOf(
            "noteId" to noteId, "body" to BODY, "direction" to direction, "createdAt" to created,
            "readAt" to if (direction == "SENT") created else null, "favorite" to false,
            "counterpartId" to counterpart, "counterpartNickname" to nickname,
            "canReply" to (direction == "INBOX"), "contextHref" to null,
        )

        fun page(item: Map<String, Any?>) = mapOf(
            "content" to listOf(item), "totalElements" to 1, "totalPages" to 1, "number" to 0, "size" to 10,
        )
        assertOk(sentList, page(view("SENT", RECIPIENT, RECIPIENT_NICKNAME)))
        assertOk(inboxList, page(view("INBOX", SENDER, SENDER_NICKNAME)))
        assertOk(detail, view("INBOX", SENDER, SENDER_NICKNAME))
        assertOk(
            notifications,
            mapOf(
                "content" to listOf(
                    mapOf(
                        "noteId" to noteId, "kind" to "PRIVATE_NOTE", "actorId" to SENDER,
                        "actorNickname" to SENDER_NICKNAME, "createdAt" to created, "read" to false, "href" to "/notes/$noteId",
                    ),
                ),
                "nextCursor" to null,
                "unreadCount" to 1,
            ),
        )
    }

    @Test
    fun `같은 요청 번호와 같은 내용일 때__다시 보내면__같은 쪽지를 돌려주고 아무것도 더 쓰거나 알리지 않는다`() {
        // given
        val sender = token(SENDER)
        val incoming = openStream(token(RECIPIENT))
        incoming.await("ready", 1)
        val noteId = noteIdOf(send(first, sender))
        incoming.await("note", 1)

        // when
        val replay = withoutWrites { send(first, sender) }

        // then
        assertOk(replay, mapOf("noteId" to noteId))
        assertThat(noteRows()).hasSize(1)
        incoming.awaitExactly("note", 1)
    }

    @Test
    fun `같은 요청 번호와 다른 내용일 때__보내면__409로 거부하고 아무것도 쓰거나 알리지 않는다`() {
        // given
        val sender = token(SENDER)
        val incoming = openStream(token(RECIPIENT))
        incoming.await("ready", 1)
        send(first, sender)
        incoming.await("note", 1)

        // when
        val conflict = withoutWrites { send(first, sender, RAW_BODY + "changed") }

        // then
        assertRejected(conflict, 409, "같은 전송 키로 다른 내용을 보낼 수 없습니다.")
        incoming.awaitExactly("note", 1)
    }

    @Test
    fun `쪽지 쓰기 권한이 없을 때__보내면__저장소 503이고 아무 행도 알림도 없으며 같은 번호로 복구하면 한 번 보낸다`() {
        // given
        val sender = token(SENDER)
        val incoming = openStream(token(RECIPIENT))
        incoming.await("ready", 1)
        val started = Instant.now()

        // when
        val failed = ContractPostgres.withRevoked("INSERT(body)", "pawbridge_community.private_notes") {
            withoutWrites { send(first, sender) }
        }
        val recovered = send(first, sender)
        val finished = Instant.now()

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        val noteId = noteIdOf(recovered)
        assertCommitted(first, noteId, started, finished)
        incoming.awaitExactly("note", 1)
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @CsvSource(
        "INSERT(direction),pawbridge_community.private_note_mailboxes",
        "INSERT(request_hash),pawbridge_community.private_note_requests",
    )
    fun `뒤쪽 쓰기가 실패할 때__보내면__앞서 쓴 행까지 되돌리고 알림도 없으며 복구하면 한 번 보낸다`(privilege: String, table: String) {
        // given
        val sender = token(SENDER)
        val incoming = openStream(token(RECIPIENT))
        incoming.await("ready", 1)
        val started = Instant.now()

        // when
        val failed = ContractPostgres.withRevoked(privilege, table) {
            withoutWrites { send(first, sender) }
        }
        val recovered = send(first, sender)
        val finished = Instant.now()

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertCommitted(first, noteIdOf(recovered), started, finished)
        incoming.awaitExactly("note", 1)
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__보내면__회원 503이고 아무 행도 알림도 없으며 같은 번호로 복구하면 한 번 보낸다`() {
        // given
        val sender = token(SENDER)
        val incoming = openStream(token(RECIPIENT))
        incoming.await("ready", 1)
        val started = Instant.now()

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { send(second, sender) }
        }
        val recovered = send(second, sender)
        val finished = Instant.now()

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertCommitted(second, noteIdOf(recovered), started, finished)
        incoming.awaitExactly("note", 1)
    }

    @Test
    fun `토큰이 없거나 이웃 경로일 때__호출하면__본문이 깨져도 401과 403으로 거부한다`() {
        // given
        val sender = token(SENDER)

        // when
        val missing = withoutWrites { call("POST", NOTES, null, "{") }
        val extra = withoutWrites { call("POST", "$NOTES/extra/extra", sender, "{}") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(extra, 403, "접근할 수 없습니다.")
        assertThat(noteRows()).isEmpty()
    }

    private companion object {
        const val NOTES = "/api/v1/notes"
        const val SENDER = 9_984_001L
        const val RECIPIENT = 9_984_002L
        const val SENDER_NICKNAME = " F29sender9984001 "
        const val RECIPIENT_NICKNAME = "F29recipient9984002"
        const val BODY = "F29 owned synthetic note 🦊"
        const val RAW_BODY = " \n$BODY \t"
    }
}
