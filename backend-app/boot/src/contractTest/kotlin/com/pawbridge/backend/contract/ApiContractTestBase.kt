package com.pawbridge.backend.contract

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

/**
 * Base of the HTTP contracts that exercise the public API of the running application.
 *
 * It adds what every such contract needs on top of [ContractTestBase]: signed bearer tokens (valid and
 * deliberately broken), request and multipart helpers that return the HTTP status together with the parsed
 * envelope, observation of the database and the fake object storage, and a log capture.
 *
 * Isolation: before each test the rows of every table and the fake storage are recorded; after it every table
 * that changed is put back exactly as it was. A test therefore does not depend on the order of the others and a
 * failing test cannot leak rows into the next one. The restore runs with foreign keys disabled on a superuser
 * connection, which is test tooling only; the application itself always runs as the restricted application role.
 */
abstract class ApiContractTestBase : ContractTestBase() {
    @Autowired
    protected lateinit var settings: PostWriteSettings

    private lateinit var baseline: Map<String, String>

    @BeforeEach
    fun recordBaselineAndResetStorage() {
        baseline = ContractPostgres.snapshot()
        ContractFakeS3.reset()
    }

    @AfterEach
    fun restoreBaselineAndCheckConnections() {
        val leftOver = ContractPostgres.snapshot().filter { (table, rows) -> baseline[table] != rows }.keys
        ContractPostgres.adminConnection { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
            for (table in leftOver) {
                connection.createStatement().use { it.execute("DELETE FROM $table") }
                connection.prepareStatement(
                    "INSERT INTO $table OVERRIDING SYSTEM VALUE " +
                        "SELECT * FROM jsonb_populate_recordset(NULL::$table, ?::jsonb)",
                ).use {
                    it.setString(1, baseline.getValue(table))
                    it.executeUpdate()
                }
            }
            connection.commit()
        }
        assertThat(ContractPostgres.snapshot() == baseline).`as`("Every table is back to its recorded rows").isTrue()
        assertNoTransactionOpen()
    }

    /** The application hands every connection back: no session of the application role is inside a transaction. */
    protected fun assertNoTransactionOpen() {
        val open = ContractPostgres.admin.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND (state = 'idle in transaction' OR xact_start IS NOT NULL)",
            Long::class.javaObjectType,
            ContractPostgres.APP_ROLE,
        )
        assertThat(open).`as`("Application sessions still inside a transaction").isZero()
    }

    // ---- HTTP -------------------------------------------------------------------------------------------------

    /** One HTTP answer: the status, the raw text, the headers and, when the text is JSON, the parsed envelope. */
    protected class Reply(val status: Int, val text: String, val headers: HttpHeaders) {
        val body: JsonNode by lazy { MAPPER.readTree(text) }
        val code: Int get() = body.get("code").asInt()
        val message: String get() = body.get("message").asText()
        val data: JsonNode get() = body.get("data")
    }

    protected fun call(
        method: String,
        path: String,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): Reply {
        val builder = HttpRequest.newBuilder(uri(path))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType)
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(
            method,
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
        )
        val response = send(builder)
        return Reply(response.statusCode(), response.body(), response.headers())
    }

    protected fun get(path: String, token: String? = null, headers: Map<String, String> = emptyMap()) =
        call("GET", path, token, headers)

    protected fun postJson(path: String, json: String, token: String? = null, headers: Map<String, String> = emptyMap()) =
        call("POST", path, token, headers, json.toByteArray(Charsets.UTF_8), "application/json")

    protected fun putJson(path: String, json: String, token: String? = null, headers: Map<String, String> = emptyMap()) =
        call("PUT", path, token, headers, json.toByteArray(Charsets.UTF_8), "application/json")

    protected fun multipart(
        method: String,
        path: String,
        fields: Map<String, String>,
        files: List<Upload> = emptyList(),
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Reply {
        val body = Multipart.build(fields, files)
        return call(method, path, token, headers, body.bytes, body.contentType)
    }

    // ---- Tokens -----------------------------------------------------------------------------------------------

    protected fun accessToken(userId: Long, role: String = "ROLE_USER", careRegNo: String? = "CONTRACT-CARE"): String {
        val now = Instant.now()
        val builder = Jwts.builder()
            .subject("contract-$userId@fixture.invalid")
            .claim("userId", userId)
            .claim("name", "Contract synthetic")
            .claim("role", role)
        if (careRegNo != null) {
            builder.claim("careRegNo", careRegNo)
        }
        return builder.issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(600))).signWith(settings.jwtKey()).compact()
    }

    /** The nine tokens the authentication filter must reject, by name. */
    protected fun rejectedTokens(userId: Long = 42): Map<String, String> {
        val now = Instant.now()
        fun signed(claims: Map<String, Any?>, expiresInSeconds: Long = 600, key: javax.crypto.SecretKey = settings.jwtKey()): String {
            val builder = Jwts.builder().claims(claims)
            return builder.issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expiresInSeconds))).signWith(key).compact()
        }
        val valid = mapOf("sub" to "contract-$userId@fixture.invalid", "userId" to userId, "name" to "Contract synthetic", "role" to "ROLE_USER")
        val foreignKey = Keys.hmacShaKeyFor(ByteArray(64) { (it * 7 + 3).toByte() })
        return linkedMapOf(
            "refresh" to signed(emptyMap()),
            "expired" to signed(valid, -60),
            "forged" to signed(valid, key = foreignKey),
            "missingId" to signed(mapOf("role" to "ROLE_USER")),
            "zeroId" to signed(mapOf("userId" to 0, "role" to "ROLE_USER")),
            "negativeId" to signed(mapOf("userId" to -1, "role" to "ROLE_USER")),
            "stringId" to signed(mapOf("userId" to "42", "role" to "ROLE_USER")),
            "missingRole" to signed(mapOf("userId" to userId)),
            "numericRole" to signed(mapOf("userId" to userId, "role" to 42)),
        )
    }

    /** What the authentication filter answers for each rejected token. */
    protected fun rejectionMessage(name: String): String {
        return if (name == "expired" || name == "forged") "유효하지 않거나 만료된 토큰입니다." else "토큰 파싱에 실패했습니다."
    }

    // ---- Database ---------------------------------------------------------------------------------------------

    protected fun execute(sql: String, vararg arguments: Any?) {
        ContractPostgres.admin.update(sql, *arguments)
    }

    protected fun scalar(sql: String, vararg arguments: Any?): String? {
        return ContractPostgres.admin.query(sql, { rows, _ -> rows.getString(1) }, *arguments).firstOrNull()
    }

    protected fun count(sql: String, vararg arguments: Any?): Long = scalar(sql, *arguments)!!.toLong()

    protected fun seedUser(userId: Long, nickname: String, role: String = "ROLE_USER", createdAt: String? = "2025-01-01 01:02:03.123456") {
        execute(
            "INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,password,care_reg_no,provider_id," +
                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?::timestamp,?::timestamp)",
            userId,
            nickname,
            "contract-$userId@fixture.invalid",
            "Contract $userId",
            "GOOGLE",
            role,
            "CONTRACT_PASSWORD_DO_NOT_EXPOSE",
            "CONTRACT_CARE_DO_NOT_EXPOSE",
            "contract-provider-$userId",
            createdAt,
            "1997-02-03 04:05:06.654321",
        )
    }

    protected fun seedPost(
        postId: Long,
        authorId: Long,
        board: String = "COMMUNICATION",
        title: String = "Contract title",
        content: String = "Contract content",
        imageUrls: String = "[]",
        deletedAt: String? = null,
        createdAt: String = "2025-01-01 01:02:03.123456",
        updatedAt: String = "2025-01-02 01:02:03.123456",
    ) {
        execute(
            "INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,image_urls,deleted_at," +
                "created_at,updated_at) VALUES(?,?,?,?,?,?::json,?::timestamp,?::timestamp,?::timestamp)",
            postId, authorId, title, content, board, imageUrls, deletedAt, createdAt, updatedAt,
        )
    }

    protected fun seedComment(
        commentId: Long,
        postId: Long,
        authorId: Long,
        content: String = "Contract comment",
        createdAt: String = "2025-01-01 01:02:03.123456",
        updatedAt: String = "2025-01-02 01:02:03.123456",
        deletedAt: String? = null,
    ) {
        execute(
            "INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,created_at,updated_at,deleted_at) " +
                "VALUES(?,?,?,?,?::timestamp,?::timestamp,?::timestamp)",
            commentId, postId, authorId, content, createdAt, updatedAt, deletedAt,
        )
    }

    /** One row as JSON text, or null. */
    protected fun rowJson(table: String, idColumn: String, id: Long): String? =
        scalar("SELECT to_jsonb(t)::text FROM $table t WHERE $idColumn = ?", id)

    /** The JSON node of [rowJson], for field-wise comparison. */
    protected fun row(table: String, idColumn: String, id: Long): JsonNode =
        MAPPER.readTree(rowJson(table, idColumn, id) ?: error("No row $id in $table"))

    /** A copy of a JSON row without the named fields, for comparing the rest of a row that legitimately changed. */
    protected fun omitting(row: JsonNode, vararg fields: String): JsonNode =
        row.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().without(fields.toList())

    // ---- Observation ------------------------------------------------------------------------------------------

    /**
     * Runs [action] and proves that it left every table of both schemas and the fake object storage exactly as
     * they were: no business row changed and no request reached the storage.
     */
    protected fun <T> assertNoSideEffects(action: () -> T): T {
        val tables = ContractPostgres.snapshot()
        val storage = storageState()
        val result = action()
        assertThat(ContractPostgres.snapshot() == tables).`as`("No table changed").isTrue()
        assertThat(storageState()).`as`("The storage saw nothing").isEqualTo(storage)
        assertNoTransactionOpen()
        return result
    }

    /** Requests and stored objects of the fake storage, comparable. */
    protected fun storageState(): List<String> {
        val requests = ContractFakeS3.requests().map { "${it.method} ${it.key} ${it.status} ${it.size}" }
        val objects = ContractFakeS3.objects().map { (key, stored) -> "$key ${stored.contentType} ${stored.sha256}" }
        return requests + objects
    }

    /** Captures what the application logs at its configured levels, including stack traces, while [action] runs. */
    protected fun <T> capturingLogs(action: () -> T): Pair<T, String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        root.addAppender(appender)
        try {
            val result = action()
            return result to appender.list.joinToString("\n") { it.formattedMessage + "\n" + ThrowableProxyUtil.asString(it.throwableProxy) }
        } finally {
            root.detachAppender(appender)
        }
    }

    /** The SQLState behind a failure, or null. */
    protected fun sqlState(failure: Throwable?): String? {
        var current = failure
        var depth = 0
        while (current != null && depth++ < 16) {
            if (current is SQLException && current.sqlState != null) {
                return current.sqlState
            }
            current = current.cause
        }
        return null
    }

    // ---- Time -------------------------------------------------------------------------------------------------

    /**
     * What the API shows for a `TIMESTAMP(6)` value written by the database: the stored wall clock is read as
     * UTC and shown in the JVM zone (UTC in the default run, Asia/Seoul in the time-zone run).
     */
    protected fun shownInJvmZone(stored: String): String {
        return LocalDateTime.parse(stored.replace(' ', 'T')).atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    companion object {
        @JvmStatic
        protected val MAPPER: ObjectMapper = ObjectMapper()
    }
}

/** A file part of a multipart request. */
class Upload(val name: String, val mime: String, val content: ByteArray)

/** A multipart/form-data body with a fixed boundary. */
class Multipart private constructor(val bytes: ByteArray, val contentType: String) {
    companion object {
        private const val BOUNDARY = "ContractSyntheticBoundary"

        fun build(fields: Map<String, String>, files: List<Upload>): Multipart {
            val out = java.io.ByteArrayOutputStream()
            fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
            for ((key, value) in fields) {
                write("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
            }
            for (upload in files) {
                write(
                    "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"files\"; filename=\"${upload.name}\"\r\n" +
                        "Content-Type: ${upload.mime}\r\n\r\n",
                )
                out.write(upload.content)
                write("\r\n")
            }
            write("--$BOUNDARY--\r\n")
            return Multipart(out.toByteArray(), "multipart/form-data; boundary=$BOUNDARY")
        }
    }
}
