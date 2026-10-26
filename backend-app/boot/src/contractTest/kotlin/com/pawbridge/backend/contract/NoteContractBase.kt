package com.pawbridge.backend.contract

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import io.jsonwebtoken.Jwts
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

/**
 * Shared plumbing of the private-note contracts: signed tokens, real HTTP calls, rows seeded and observed through
 * the superuser connection, and proof that a test leaves every table and every log line as it found them.
 *
 * Each subclass owns one id range. Rows are seeded inside it and removed after every test, so tests do not depend
 * on their order and never touch rows of another class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class NoteContractBase : ContractTestBase() {
    @Autowired
    protected lateinit var settings: PostWriteSettings

    /** Every user, member and note id of the class lies in this range. */
    protected abstract val ids: LongRange

    /** Values that must never appear in an application log line written during the test. */
    protected val secrets = LinkedHashSet<String>()

    private val noteIds = LinkedHashSet<UUID>()
    private val logs = ListAppender<ILoggingEvent>()
    private lateinit var baseline: Map<String, String>

    @BeforeAll
    fun captureBaseline() {
        baseline = ContractPostgres.snapshot()
    }

    @BeforeEach
    fun startLogCapture() {
        logs.list.clear()
        logs.start()
        rootLogger().addAppender(logs)
        secrets.clear()
        secrets.add(ContractPostgres.appPassword)
        secrets.add(String(settings.jwtKey().encoded, StandardCharsets.UTF_8))
    }

    @AfterEach
    fun removeSeededRowsAndCheckLogs() {
        rootLogger().detachAppender(logs)
        logs.stop()
        try {
            removeRows()
        } finally {
            noteIds.clear()
        }
        assertThat(ContractPostgres.snapshot() == baseline)
            .`as`("Every table of both schemas is restored after the test")
            .isTrue()
        val text = capturedLog()
        for (secret in secrets) {
            assertThat(secret.isNotBlank() && text.contains(secret))
                .`as`("The application log never contains a credential, token or private value")
                .isFalse()
        }
    }

    /** Every application log line written since the test began. */
    protected fun capturedLog(): String = logs.list.joinToString("\n") { event ->
        event.formattedMessage + (event.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "")
    }

    private fun rootLogger(): Logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private fun removeRows() {
        val admin = ContractPostgres.admin
        val low = ids.first
        val high = ids.last
        val notes = admin.queryForList(
            "SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id BETWEEN ? AND ? " +
                "OR recipient_id BETWEEN ? AND ? UNION SELECT note_id FROM pawbridge_community.private_note_mailboxes " +
                "WHERE member_id BETWEEN ? AND ?",
            UUID::class.java,
            low, high, low, high, low, high,
        )
        for (note in notes + noteIds) {
            admin.update("DELETE FROM pawbridge_community.private_notes WHERE note_id = ?", note)
        }
        admin.update("DELETE FROM pawbridge_community.private_note_requests WHERE sender_id BETWEEN ? AND ?", low, high)
        admin.update(
            "DELETE FROM pawbridge_community.private_note_blocks WHERE member_id BETWEEN ? AND ? " +
                "OR blocked_id BETWEEN ? AND ?",
            low, high, low, high,
        )
        admin.update("DELETE FROM pawbridge_user.contact_deletions WHERE user_id BETWEEN ? AND ?", low, high)
        admin.update("DELETE FROM pawbridge_community.private_note_members WHERE member_id BETWEEN ? AND ?", low, high)
        admin.update("DELETE FROM pawbridge_user.users WHERE user_id BETWEEN ? AND ?", low, high)
    }

    // ---- seeding ------------------------------------------------------------------------------------------

    protected fun seedUser(id: Long, nickname: String, role: String = "ROLE_USER") {
        check(id in ids)
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_user.users(user_id, nickname, email, name, provider, role) " +
                "VALUES (?, ?, ?, ?, 'GOOGLE', ?)",
            id, nickname, "note-$id@fixture.invalid", "Note $id", role,
        )
        secrets.add("note-$id@fixture.invalid")
        secrets.add("Note $id")
        if (nickname.isNotBlank()) {
            secrets.add(nickname.trim())
        }
    }

    protected fun seedMember(id: Long) {
        check(id in ids)
        ContractPostgres.admin.update("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?)", id)
    }

    protected fun seedPendingDeletion(id: Long) {
        ContractPostgres.admin.update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", id)
    }

    protected fun seedBlock(member: Long, blocked: Long, createdAt: String = "2026-10-18T15:00:00.123456Z") {
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at) " +
                "VALUES (?, ?, ?::timestamptz)",
            member, blocked, createdAt,
        )
    }

    /** Inserts one note with its mailbox rows. A null owner of a mailbox means no row for that side. */
    protected fun seedNote(
        note: UUID,
        sender: Long?,
        recipient: Long?,
        body: String,
        senderBox: Mailbox? = null,
        recipientBox: Mailbox? = null,
        createdAt: String = "2026-10-18T15:00:00.123456Z",
        mailboxCreatedAt: String = createdAt,
        expiresAt: String = "2099-01-01T00:00:00Z",
        replyTo: UUID? = null,
        context: Pair<String, Long>? = null,
    ) {
        noteIds.add(note)
        secrets.add(body.trim())
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_community.private_notes(note_id, sender_id, recipient_id, body, reply_to, " +
                "context_type, context_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
            note, sender, recipient, body, replyTo, context?.first, context?.second, createdAt, expiresAt,
        )
        if (sender != null && senderBox != null) {
            seedMailbox(sender, note, "SENT", senderBox, mailboxCreatedAt)
        }
        if (recipient != null && recipientBox != null) {
            seedMailbox(recipient, note, "INBOX", recipientBox, mailboxCreatedAt)
        }
    }

    protected fun seedMailbox(member: Long, note: UUID, direction: String, box: Mailbox, createdAt: String) {
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_community.private_note_mailboxes(member_id, note_id, direction, read_at, favorite, " +
                "created_at) VALUES (?, ?, ?, ?::timestamptz, ?, ?::timestamptz)",
            member, note, direction, box.readAt, box.favorite, createdAt,
        )
    }

    protected class Mailbox(val readAt: String? = null, val favorite: Boolean = false)

    protected fun scalar(query: String, vararg args: Any?): String? {
        return ContractPostgres.admin.queryForObject(query, String::class.java, *args)
    }

    protected fun row(query: String, vararg args: Any?): Map<String, Any?> {
        return ContractPostgres.admin.queryForMap(query, *args)
    }

    protected fun hasMember(id: Long): Boolean {
        return scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id = ?", id) == "1"
    }

    protected fun readAt(member: Long, note: UUID): String? {
        return scalar(
            "SELECT read_at::text FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?",
            member, note,
        )
    }

    protected fun noteExists(note: UUID): Boolean {
        return scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id = ?", note) == "1"
    }

    protected fun mailboxExists(member: Long, note: UUID): Boolean {
        return scalar(
            "SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?",
            member, note,
        ) == "1"
    }

    /** No pooled application connection may hold an open transaction once a response was received. */
    protected fun assertNoOpenTransaction() {
        assertThat(
            scalar(
                "SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND " +
                    "(state = 'idle in transaction' OR xact_start IS NOT NULL)",
                ContractPostgres.APP_ROLE,
            ),
        ).`as`("The request left a transaction open").isEqualTo("0")
    }

    // ---- tokens -------------------------------------------------------------------------------------------

    /** A token like the login endpoint issues. `lifetime` null leaves out the expiry claim. */
    protected fun token(
        userId: Long,
        role: String = "ROLE_USER",
        lifetime: Duration? = Duration.ofMinutes(10),
    ): String {
        val now = Instant.now()
        val builder = Jwts.builder()
            .subject("note-$userId@fixture.invalid")
            .claim("userId", userId)
            .claim("name", "Note $userId")
            .claim("role", role)
            .issuedAt(Date.from(now))
        if (lifetime != null) {
            builder.expiration(Date.from(now.plus(lifetime)))
        }
        val value = builder.signWith(settings.jwtKey()).compact()
        secrets.add(value)
        return value
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------

    protected fun call(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Reply {
        val builder = HttpRequest.newBuilder(uri(path))
        // Identity headers a client might forge: the application must trust the signed token only.
        builder.header("X-User-Id", "1")
        builder.header("X-User-Role", "ROLE_ADMIN")
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        for ((name, value) in extraHeaders) {
            builder.header(name, value)
        }
        if (body == null) {
            builder.method(method, BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.method(method, BodyPublishers.ofString(body))
        }
        val response: HttpResponse<String> = send(builder)
        return Reply(response.statusCode(), response.headers(), response.body())
    }

    protected fun get(path: String, token: String? = null): Reply = call("GET", path, token)

    protected class Reply(val status: Int, private val headers: HttpHeaders, val text: String) {
        fun header(name: String): String? = headers.firstValue(name).orElse(null)

        val json: JsonNode get() = MAPPER.readTree(text)
    }

    /** Success body of the common envelope: `{code, message, data}` with the original message. */
    protected fun assertOk(reply: Reply, data: Any?) {
        assertBusiness(reply, 200, obj("code" to 200, "message" to SUCCESS, "data" to data))
    }

    /** Failure body of the note controllers: only `message`. */
    protected fun assertRejected(reply: Reply, status: Int, message: String) {
        assertBusiness(reply, status, obj("message" to message))
    }

    /** A note controller answer: exact status and body, `no-store`, and never a cookie. */
    protected fun assertBusiness(reply: Reply, status: Int, expected: JsonNode) {
        assertThat(reply.status).`as`(reply.text).isEqualTo(status)
        assertThat(reply.header("Set-Cookie")).isNull()
        assertThat(reply.header("Cache-Control")).isEqualTo("no-store")
        assertThat(reply.json).isEqualTo(expected)
    }

    /** An answer of the Spring Security layer (401, 403) or of the shared error handler. */
    protected fun assertSecurity(reply: Reply, status: Int, message: String) {
        assertThat(reply.status).`as`(reply.text).isEqualTo(status)
        assertThat(reply.header("Set-Cookie")).isNull()
        assertThat(reply.header("Cache-Control")).isEqualTo(SECURITY_CACHE_CONTROL)
        assertThat(reply.json).isEqualTo(obj("code" to status, "message" to message, "data" to null))
    }

    /** An empty 200 answer of a mutation: `no-store`, no cookie, no body. */
    protected fun assertEmptyOk(reply: Reply) {
        assertThat(reply.status).`as`(reply.text).isEqualTo(200)
        assertThat(reply.header("Set-Cookie")).isNull()
        assertThat(reply.header("Cache-Control")).isEqualTo("no-store")
        assertThat(reply.text).isEmpty()
    }

    /** Tables whose rows differ between two [ContractPostgres.snapshot] results. */
    protected fun changedTables(before: Map<String, String>, after: Map<String, String>): Set<String> {
        return before.keys.filter { before[it] != after[it] }.toSet()
    }

    /** Runs [action] and returns its result with the tables it changed. */
    protected fun <T> observingChanges(action: () -> T): Pair<T, Set<String>> {
        val before = ContractPostgres.snapshot()
        val result = action()
        assertNoOpenTransaction()
        return result to changedTables(before, ContractPostgres.snapshot())
    }

    /** Runs [action] and proves that it changed no row of either schema. */
    protected fun <T> withoutWrites(action: () -> T): T {
        val before = ContractPostgres.snapshot()
        val result = action()
        assertThat(ContractPostgres.snapshot() == before).`as`("The request wrote a row").isTrue()
        assertNoOpenTransaction()
        return result
    }

    // ---- streams ------------------------------------------------------------------------------------------

    private val streams = ArrayList<NoteEventStream>()

    protected fun openStream(token: String?, query: String = ""): NoteEventStream {
        return track(NoteEventStream.open(uri("/api/v1/notes/stream$query"), token))
    }

    /** Closes the stream after the test, however the test ends. */
    protected fun track(stream: NoteEventStream): NoteEventStream {
        streams.add(stream)
        return stream
    }

    @AfterEach
    fun closeStreams() {
        streams.forEach { it.close() }
        streams.clear()
    }

    protected companion object {
        const val SUCCESS = "요청이 성공적으로 처리되었습니다."
        const val STORAGE_UNAVAILABLE = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
        const val MEMBER_UNAVAILABLE = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        const val WITHDRAWN = "탈퇴한 회원"
        const val SECURITY_CACHE_CONTROL = "no-cache, no-store, max-age=0, must-revalidate"
        const val STORED_TIME = "2026-10-18T15:00:00.123456Z"
        val MAPPER = ObjectMapper()

        /** A JSON tree built the way a client parses it, so integers, booleans and strings keep their JSON types. */
        fun obj(vararg pairs: Pair<String, Any?>): JsonNode {
            return MAPPER.readTree(MAPPER.writeValueAsString(linkedMapOf(*pairs)))
        }

        fun tree(value: Any?): JsonNode = MAPPER.readTree(MAPPER.writeValueAsString(value))

        fun timestamp(value: String): Timestamp = Timestamp.from(Instant.parse(value))
    }
}
