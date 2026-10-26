package com.pawbridge.backend.contract

import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.RefreshTokens
import com.zaxxer.hikari.HikariDataSource
import io.jsonwebtoken.Jwts
import java.net.http.HttpRequest
import java.sql.SQLException
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Cookie refresh sessions on the real application wiring and a real PostgreSQL, with the application role
 * holding only the column privileges in `schema/app-grants.sql`.
 *
 * The rotation, reuse and eviction rules are asserted by what the database holds afterwards. Token values and
 * hashes are only compared, never printed. Rows are seeded and removed through the superuser connection, so
 * every test must leave all tables as it found them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSessionContractTest : ContractTestBase() {
    @Autowired
    private lateinit var sessions: AuthSessions

    @Autowired
    private lateinit var store: JdbcAuthSessionStore

    @Autowired
    @Qualifier("writeTransactionManager")
    private lateinit var manager: JdbcTransactionManager

    @Autowired
    @Qualifier("writeDataSource")
    private lateinit var pool: HikariDataSource

    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var appJdbc: JdbcTemplate

    @Autowired
    private lateinit var settings: PostWriteSettings

    private val users = LinkedHashSet<Long>()
    private lateinit var transaction: TransactionTemplate
    private lateinit var baseline: Map<String, String>

    @BeforeAll
    fun prepare() {
        assertThat(ZoneId.systemDefault().id).isEqualTo(System.getProperty("pawbridge.contract.expected-zone"))
        transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id BETWEEN $BASE AND ${BASE + 99}"))
            .isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions")).isEqualTo("0")
        baseline = ContractPostgres.snapshot()
    }

    @AfterEach
    fun removeSeededRowsAndCheckEveryTable() {
        try {
            cleanup()
        } finally {
            returned()
        }
        assertThat(ContractPostgres.snapshot() == baseline)
            .`as`("Every table of both schemas restores without logging token values")
            .isTrue()
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions")).isEqualTo("0")
    }

    @Test
    @Tag("timezone-sensitive")
    fun `활성 세션일 때__리프레시하면__현재 클레임을 주고 퇴역 해시를 남긴다`() {
        // given
        seedUser(BASE + 1)
        seedUser(BASE + 2)
        val first = login(BASE + 1)
        val other = login(BASE + 2)
        assertThat(first.maxAgeSeconds).isBetween(REFRESH_MS / 1000 - 1, REFRESH_MS / 1000)
        val created = scalar("SELECT created_at::text FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}")
        val untouched = sessionRows(BASE + 2)

        // when
        val rotated = sessions.refresh(first.value) as AuthSessions.Rotated

        // then
        val claims = Jwts.parser().verifyWith(settings.jwtKey()).build()
            .parseSignedClaims(rotated.accessToken).payload
        assertThat(claims.get("userId", Long::class.javaObjectType)).isEqualTo(BASE + 1)
        assertThat(claims.subject).isEqualTo("auth-${BASE + 1}@fixture.invalid")
        assertThat(claims.get("name", String::class.java)).isEqualTo("Auth synthetic")
        assertThat(claims.get("role", String::class.java)).isEqualTo("ROLE_ADMIN")
        assertThat(claims.get("careRegNo", String::class.java)).isEqualTo("CARE_AUTH")
        assertThat(claims.expiration.time - claims.issuedAt.time).isBetween(120_000L, 121_000L)
        val next = rotated.refreshToken!!.value!!
        assertThat(RefreshTokens.wellFormed(next)).isTrue()
        assertThat(next == first.value).isFalse()
        assertThat(rotated.refreshToken!!.maxAgeSeconds).isBetween(REFRESH_MS / 1000 - 1, REFRESH_MS / 1000)
        val where = " FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}"
        assertThat(
            scalar(
                "SELECT (token_hash=${literal(RefreshTokens.hash(next))}" +
                    " AND rotated_at = last_used_at AND revoked_at IS NULL)::int$where",
            ),
        ).isEqualTo("1")
        assertThat(scalar("SELECT created_at::text$where")).isEqualTo(created)
        assertThat(
            scalar(
                "SELECT count(*) FROM pawbridge_user.auth_session_token_history h JOIN pawbridge_user.auth_sessions s" +
                    " ON s.session_id = h.session_id WHERE h.token_hash=${literal(RefreshTokens.hash(first.value!!))}" +
                    " AND h.rotated_at = s.rotated_at",
            ),
        ).`as`("The retired hash is kept with the rotation time").isEqualTo("1")
        assertThat(
            scalar(
                "SELECT (SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash IN " +
                    "(${literal(first.value!!)},${literal(next)}))" +
                    "+(SELECT count(*) FROM pawbridge_user.auth_session_token_history WHERE token_hash=" +
                    "${literal(first.value!!)})",
            ),
        ).`as`("Raw tokens are never stored").isEqualTo("0")
        assertThat(sessions.refresh(next)).isInstanceOf(AuthSessions.Rotated::class.java)
        assertThat(sessionRows(BASE + 2) == untouched).isTrue()
        assertThat(other.value == next).isFalse()
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(strings = ["idle-expired", "absolute-expired", "revoked", "unknown"])
    fun `비활성이거나 모르는 토큰일 때__리프레시하면__변경 없이 거부한다`(scenario: String) {
        // given
        seedUser(BASE + 1)
        val session = login(BASE + 1)
        val column = when (scenario) {
            "idle-expired" -> "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'"
            "absolute-expired" -> "absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'"
            "revoked" -> "revoked_at = CURRENT_TIMESTAMP"
            else -> null
        }
        if (column != null) {
            execute("UPDATE pawbridge_user.auth_sessions SET $column WHERE user_id=${BASE + 1}")
        }
        val presented = if (scenario == "unknown") RefreshTokens().generate() else session.value!!
        val before = sessionRows(BASE + 1)

        // when
        val failure = catchThrowable { sessions.refresh(presented) }

        // then
        assertThat(failure).isInstanceOf(AuthSessionRejectedException::class.java)
        assertThat((failure as AuthSessionRejectedException).reason()).isEqualTo(Reason.INVALID)
        assertThat(sessionRows(BASE + 1) == before).`as`("A rejected refresh changes no session").isTrue()
    }

    @Test
    fun `유예 이후 두 세대 전 토큰일 때__리프레시하면__그 세션만 폐기하고 폐기를 유지한다`() {
        // given
        seedUser(BASE + 1)
        val stolen = login(BASE + 1)
        val otherDevice = login(BASE + 1)
        val second = sessions.refresh(stolen.value) as AuthSessions.Rotated
        val third = sessions.refresh(second.refreshToken!!.value) as AuthSessions.Rotated
        val sessionId = scalar(
            "SELECT session_id FROM pawbridge_user.auth_sessions WHERE token_hash=" +
                literal(RefreshTokens.hash(third.refreshToken!!.value!!)),
        )!!.toLong()
        moveRotationsBack(sessionId, 120)
        val otherBefore = sessionRow(otherDevice.value!!)

        // when
        val reuse = catchThrowable { sessions.refresh(stolen.value) }

        // then
        assertThat((reuse as AuthSessionRejectedException).reason()).isEqualTo(Reason.REUSED)
        assertThat(
            scalar("SELECT (revoked_at IS NOT NULL)::int FROM pawbridge_user.auth_sessions WHERE session_id=$sessionId"),
        ).`as`("The revocation committed").isEqualTo("1")
        assertThat(sessionRow(otherDevice.value!!) == otherBefore).`as`("Other sessions of the user stay").isTrue()
        val latest = catchThrowable { sessions.refresh(third.refreshToken!!.value) }
        assertThat((latest as AuthSessionRejectedException).reason()).isEqualTo(Reason.INVALID)
        assertThat(sessions.refresh(otherDevice.value)).isInstanceOf(AuthSessions.Rotated::class.java)
    }

    @Test
    fun `회전 응답을 잃었을 때__59초 뒤 퇴역 토큰이 오면__한 번만 다시 회전하고 후계를 남긴다`() {
        // given
        seedUser(BASE + 1)
        val kept = login(BASE + 1)
        val lost = sessions.refresh(kept.value) as AuthSessions.Rotated
        val sessionId = scalar(
            "SELECT session_id FROM pawbridge_user.auth_sessions WHERE token_hash=" +
                literal(RefreshTokens.hash(lost.refreshToken!!.value!!)),
        )!!.toLong()
        // Keep the retired token inside the 60-second grace window with room for the connections the
        // checks below open; the last refresh of the same token must still see it as recent.
        moveRotationsBack(sessionId, 50)

        // when
        val recovered = sessions.refresh(kept.value) as AuthSessions.Rotated

        // then
        val current = recovered.refreshToken!!.value!!
        assertThat(
            scalar(
                "SELECT (token_hash=${literal(RefreshTokens.hash(current))})::int FROM pawbridge_user.auth_sessions" +
                    " WHERE session_id=$sessionId",
            ),
        ).isEqualTo("1")
        assertThat(
            scalar(
                "SELECT count(*) FROM pawbridge_user.auth_session_token_history WHERE session_id=$sessionId" +
                    " AND token_hash IN (${literal(RefreshTokens.hash(kept.value!!))}," +
                    "${literal(RefreshTokens.hash(lost.refreshToken!!.value!!))})",
            ),
        ).isEqualTo("2")
        val afterRecovery = sessionRows(BASE + 1)
        assertThat(sessions.refresh(kept.value)).`as`("Only the latest retirement rotates again")
            .isInstanceOf(AuthSessions.AccessOnly::class.java)
        assertThat(sessionRows(BASE + 1) == afterRecovery).isTrue()
        assertThat(sessions.refresh(current)).isInstanceOf(AuthSessions.Rotated::class.java)
    }

    @Test
    fun `같은 퇴역 토큰의 유예 요청 둘일 때__동시에 리프레시하면__한 요청만 다시 회전한다`() {
        // given
        seedUser(BASE + 1)
        val retired = login(BASE + 1)
        sessions.refresh(retired.value)
        val barrier = CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        val attempt = Callable<Any> {
            barrier.await(8, TimeUnit.SECONDS)
            try {
                sessions.refresh(retired.value)
            } catch (failure: RuntimeException) {
                failure
            }
        }

        try {
            // when
            val first = workers.submit(attempt)
            val second = workers.submit(attempt)
            val outcomes = listOf(first.get(18, TimeUnit.SECONDS), second.get(18, TimeUnit.SECONDS))

            // then
            val rotatedCount = outcomes.count { it is AuthSessions.Rotated }
            val accessOnlyCount = outcomes.count { it is AuthSessions.AccessOnly }
            assertThat(rotatedCount).`as`("Exactly one grace request rotates again").isEqualTo(1)
            assertThat(accessOnlyCount).`as`("The other receives an access token only").isEqualTo(1)
            val winner = outcomes.first { it is AuthSessions.Rotated } as AuthSessions.Rotated
            assertThat(
                scalar(
                    "SELECT (token_hash=${literal(RefreshTokens.hash(winner.refreshToken!!.value!!))}" +
                        " AND revoked_at IS NULL)::int FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}",
                ),
            ).isEqualTo("1")
        } finally {
            workers.shutdownNow()
            assertThat(workers.awaitTermination(8, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `한 사용자가 여섯 번 로그인할 때__세션을 만들면__최근 사용한 다섯 개만 활성으로 둔다`() {
        // given
        seedUser(BASE + 1)
        val logins = ArrayList<IssuedRefreshToken>()
        for (index in 0 until 5) {
            logins.add(login(BASE + 1))
        }
        // Using the first session makes the second one the least recently used.
        val refreshed = sessions.refresh(logins.first().value) as AuthSessions.Rotated

        // when
        logins.add(login(BASE + 1))

        // then
        assertThat(
            scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1} AND revoked_at IS NULL"),
        ).isEqualTo("5")
        assertThat(
            scalar(
                "SELECT (revoked_at IS NOT NULL)::int FROM pawbridge_user.auth_sessions WHERE token_hash=" +
                    literal(RefreshTokens.hash(logins[1].value!!)),
            ),
        ).isEqualTo("1")
        assertThat(
            scalar(
                "SELECT (revoked_at IS NULL)::int FROM pawbridge_user.auth_sessions WHERE token_hash=" +
                    literal(RefreshTokens.hash(refreshed.refreshToken!!.value!!)),
            ),
        ).isEqualTo("1")
        val evicted = catchThrowable { sessions.refresh(logins[1].value) }
        assertThat((evicted as AuthSessionRejectedException).reason()).isEqualTo(Reason.INVALID)
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(strings = ["logout", "revoke-all", "cleanup"])
    fun `로그아웃 전체 폐기 정리일 때__쓰면__대상 세션만 바꾼다`(operation: String) {
        // given
        seedUser(BASE + 1)
        seedUser(BASE + 2)
        val target = login(BASE + 1)
        val sibling = login(BASE + 1)
        val stranger = login(BASE + 2)
        val strangerBefore = sessionRows(BASE + 2)

        // when
        // then
        when (operation) {
            "logout" -> {
                val siblingBefore = sessionRow(sibling.value!!)
                sessions.logout(target.value)
                val afterFirst = sessionRows(BASE + 1)
                sessions.logout(target.value)
                sessions.logout(null)
                assertThat(sessionRows(BASE + 1) == afterFirst).`as`("Logout is idempotent").isTrue()
                assertThat(
                    scalar(
                        "SELECT (revoked_at IS NOT NULL)::int FROM pawbridge_user.auth_sessions WHERE token_hash=" +
                            literal(RefreshTokens.hash(target.value!!)),
                    ),
                ).isEqualTo("1")
                assertThat(sessionRow(sibling.value!!) == siblingBefore).isTrue()
            }
            "revoke-all" -> {
                transaction.executeWithoutResult { store.revokeAll(BASE + 1) }
                assertThat(
                    scalar(
                        "SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}" +
                            " AND revoked_at IS NULL",
                    ),
                ).isEqualTo("0")
            }
            else -> {
                val idle = login(BASE + 1)
                val absolute = login(BASE + 1)
                execute(
                    "UPDATE pawbridge_user.auth_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'" +
                        " WHERE token_hash=" + literal(RefreshTokens.hash(idle.value!!)),
                )
                execute(
                    "UPDATE pawbridge_user.auth_sessions SET absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'" +
                        " WHERE token_hash=" + literal(RefreshTokens.hash(absolute.value!!)),
                )
                execute(
                    "UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '2 days'" +
                        " WHERE token_hash=" + literal(RefreshTokens.hash(target.value!!)),
                )
                execute(
                    "UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'" +
                        " WHERE token_hash=" + literal(RefreshTokens.hash(sibling.value!!)),
                )
                val deleted = transaction.execute { store.deleteFinished(1000) }
                assertThat(deleted).isEqualTo(3)
                assertThat(
                    scalar(
                        "SELECT count(*) FROM pawbridge_user.auth_session_token_history h WHERE NOT EXISTS" +
                            " (SELECT 1 FROM pawbridge_user.auth_sessions s WHERE s.session_id = h.session_id)",
                    ),
                ).isEqualTo("0")
                assertThat(
                    scalar(
                        "SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash=" +
                            literal(RefreshTokens.hash(sibling.value!!)),
                    ),
                ).`as`("Recently revoked rows are kept for a day").isEqualTo("1")
            }
        }
        assertThat(sessionRows(BASE + 2) == strangerBefore).`as`("Another user's sessions stay").isTrue()
        assertThat(stranger.value == target.value).isFalse()
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(strings = ["email-read", "rotate-write", "history-write", "cleanup-delete"])
    fun `필요한 쓰기 권한 하나를 회수했을 때__호출하면__부분 변경 없이 서버 오류이고 복구된다`(denied: String) {
        // given
        seedUser(BASE + 1)
        val session = login(BASE + 1)
        if (denied == "cleanup-delete") {
            // Gives the cleanup exactly one finished row to delete.
            execute(
                "UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '2 days'" +
                    " WHERE user_id=${BASE + 1}",
            )
        }
        val (privilege, target) = when (denied) {
            "email-read" -> "SELECT(email)" to "pawbridge_user.users"
            "rotate-write" -> "UPDATE(token_hash)" to "pawbridge_user.auth_sessions"
            "history-write" -> "INSERT(token_hash)" to "pawbridge_user.auth_session_token_history"
            else -> "DELETE" to "pawbridge_user.auth_sessions"
        }
        val before = sessionRows(BASE + 1)

        // when
        ContractPostgres.withRevoked(privilege, target) {
            val failure = catchThrowable {
                if (denied == "cleanup-delete") {
                    transaction.execute { store.deleteFinished(1000) }
                } else {
                    sessions.refresh(session.value)
                }
            }

            // then
            assertThat(sqlState(failure)).isEqualTo("42501")
            assertThat(sessionRows(BASE + 1) == before).`as`("The whole transaction rolls back").isTrue()
        }

        // then
        if (denied == "cleanup-delete") {
            val deleted = transaction.execute { store.deleteFinished(1000) }
            assertThat(deleted).isEqualTo(1)
        } else {
            assertThat(sessions.refresh(session.value)).isInstanceOf(AuthSessions.Rotated::class.java)
        }
    }

    @Test
    fun `계정 행을 삭제했을 때__그 사용자의 세션을 조회하면__함께 연쇄 삭제되어 있다`() {
        // given
        seedUser(BASE + 1)
        seedUser(BASE + 2)
        login(BASE + 1)
        login(BASE + 1)
        login(BASE + 2)
        val strangerBefore = sessionRows(BASE + 2)

        // when
        val deleted = appJdbc.update("DELETE FROM pawbridge_user.users WHERE user_id=?", BASE + 1)

        // then
        assertThat(deleted).isOne()
        users.remove(BASE + 1)
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}")).isEqualTo("0")
        assertThat(sessionRows(BASE + 2) == strangerBefore).isTrue()
    }

    @Test
    fun `허용된 Origin의 쿠키 요청일 때__HTTP로 리프레시하면__새 쿠키를 주고 세션을 회전한다`() {
        // given
        seedUser(BASE + 1)
        val issued = login(BASE + 1)
        val before = sessionRows(BASE + 1)

        // when
        val rejected = refreshOverHttp(issued.value!!, "https://evil.example.test")
        val accepted = refreshOverHttp(issued.value!!, ALLOWED_ORIGIN)

        // then
        assertThat(rejected.statusCode()).isEqualTo(403)
        assertThat(accepted.statusCode()).isEqualTo(200)
        val cookie = accepted.headers().firstValue("Set-Cookie").orElse("")
        assertThat(cookie).startsWith("__Secure-pb_refresh=")
        val next = cookie.substringAfter("=").substringBefore(";")
        assertThat(RefreshTokens.wellFormed(next)).isTrue()
        assertThat(next == issued.value).isFalse()
        assertThat(sessionRows(BASE + 1) == before).`as`("The rotation changed the session").isFalse()
        assertThat(
            scalar(
                "SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}" +
                    " AND token_hash=${literal(RefreshTokens.hash(next))}",
            ),
        ).isEqualTo("1")
    }

    @Test
    fun `쿠키가 없거나 형식이 틀릴 때__리프레시하면__누락 또는 유효하지 않음으로 거부하고 세션을 건드리지 않는다`() {
        // given
        seedUser(BASE + 1)
        login(BASE + 1)
        val before = sessionRows(BASE + 1)

        // when
        val missing = listOf(null, "").map { token -> catchThrowable { sessions.refresh(token) } }
        val malformed = listOf("short", "!".repeat(43), "a".repeat(44)).map { token -> catchThrowable { sessions.refresh(token) } }

        // then
        assertThat(missing.map { (it as AuthSessionRejectedException).reason() }).containsOnly(Reason.MISSING)
        assertThat(malformed.map { (it as AuthSessionRejectedException).reason() }).containsOnly(Reason.INVALID)
        assertThat(sessionRows(BASE + 1) == before).isTrue()
    }

    @Test
    fun `방금 퇴역했지만 세션이 폐기된 토큰일 때__리프레시하면__되살리거나 다시 폐기하지 않고 거부한다`() {
        // given
        seedUser(BASE + 1)
        val first = login(BASE + 1)
        sessions.refresh(first.value)
        execute("UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE user_id=${BASE + 1}")
        val before = sessionRows(BASE + 1)

        // when
        val failure = catchThrowable { sessions.refresh(first.value) }

        // then
        assertThat((failure as AuthSessionRejectedException).reason()).isEqualTo(Reason.INVALID)
        assertThat(sessionRows(BASE + 1) == before).`as`("An inactive session is not touched again").isTrue()
    }

    @Test
    fun `회원 행이 다른 연결에 잠겨 있을 때__세션을 만들면__잠금이 풀릴 때까지 기다린 뒤 만든다`() {
        // given
        seedUser(BASE + 1)
        val executor = Executors.newSingleThreadExecutor()

        // when
        // then
        try {
            ContractPostgres.adminConnection { connection ->
                connection.autoCommit = false
                // FOR NO KEY UPDATE does not conflict with the FOR KEY SHARE lock of the session's foreign key, only with
                // the explicit FOR UPDATE of the session creation, so this wait proves that lock and not the insert.
                connection.createStatement().use {
                    it.execute("SELECT user_id FROM pawbridge_user.users WHERE user_id = ${BASE + 1} FOR NO KEY UPDATE")
                }
                val creation = executor.submit(Callable { login(BASE + 1) })
                assertThat(catchThrowable { creation.get(700, TimeUnit.MILLISECONDS) }).isInstanceOf(TimeoutException::class.java)
                assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}")).isEqualTo("0")
                connection.commit()
                assertThat(creation.get(10, TimeUnit.SECONDS).value).isNotNull()
            }
        } finally {
            executor.shutdownNow()
        }
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=${BASE + 1}")).isEqualTo("1")
    }

    private fun refreshOverHttp(token: String, origin: String) = send(
        HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
            .header("Origin", origin)
            .header("Content-Type", "application/json")
            .header("Cookie", "__Secure-pb_refresh=$token")
            .POST(HttpRequest.BodyPublishers.noBody()),
    )

    private fun login(userId: Long): IssuedRefreshToken {
        assertThat(users.contains(userId)).isTrue()
        return transaction.execute { store.create(userId) }!!
    }

    /** Sessions of the user and their token history, so a check also catches an unexpected history write. */
    private fun sessionRows(userId: Long): String {
        return scalar(
            "SELECT json_build_object(" +
                "'sessions',(SELECT COALESCE(json_agg(s ORDER BY session_id),'[]'::json) " +
                "FROM pawbridge_user.auth_sessions s WHERE user_id=$userId)," +
                "'history',(SELECT COALESCE(json_agg(h ORDER BY h.token_hash),'[]'::json) " +
                "FROM pawbridge_user.auth_session_token_history h" +
                " JOIN pawbridge_user.auth_sessions s USING (session_id) WHERE s.user_id=$userId))::text",
        )!!
    }

    /** Moves every rotation of the session back in time, keeping the session and its history consistent. */
    private fun moveRotationsBack(sessionId: Long, seconds: Int) {
        execute(
            "UPDATE pawbridge_user.auth_session_token_history SET rotated_at = rotated_at - INTERVAL '$seconds seconds'" +
                " WHERE session_id=$sessionId",
        )
        execute(
            "UPDATE pawbridge_user.auth_sessions SET rotated_at = rotated_at - INTERVAL '$seconds seconds'," +
                " last_used_at = last_used_at - INTERVAL '$seconds seconds' WHERE session_id=$sessionId",
        )
    }

    private fun sessionRow(token: String): String {
        return scalar(
            "SELECT to_jsonb(s)::text FROM pawbridge_user.auth_sessions s WHERE token_hash=" +
                literal(RefreshTokens.hash(token)),
        )!!
    }

    private fun literal(value: String): String {
        assertThat(value.matches(Regex("[A-Za-z0-9_-]+"))).isTrue()
        return "'$value'"
    }

    private fun cleanup() {
        for (id in users) {
            execute("DELETE FROM pawbridge_user.auth_sessions WHERE user_id=$id")
            assertThat(
                scalar(
                    "SELECT (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id=$id)" +
                        "+(SELECT count(*) FROM pawbridge_user.favorites WHERE user_id=$id)" +
                        "+(SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id=$id)" +
                        "+(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id=$id)",
                ),
            ).isEqualTo("0")
            execute("DELETE FROM pawbridge_user.users WHERE user_id=$id")
        }
        users.clear()
    }

    private fun execute(sql: String) {
        ContractPostgres.admin.execute(sql)
    }

    private fun scalar(sql: String): String? {
        return ContractPostgres.admin.query(sql) { rows, _ -> rows.getString(1) }.firstOrNull()
    }

    private fun sqlState(failure: Throwable?): String? {
        val pending = ArrayDeque<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        if (failure != null) {
            pending.add(failure)
        }
        while (pending.isNotEmpty() && seen.size < 32) {
            val next = pending.removeFirst()
            if (!seen.add(next)) {
                continue
            }
            if (next is SQLException && next.sqlState != null) {
                return next.sqlState
            }
            if (next.cause != null) {
                pending.add(next.cause!!)
            }
            if (next is SQLException && next.nextException != null) {
                pending.add(next.nextException)
            }
        }
        return null
    }

    /** The application pool is shared, so every test must hand all of its connections back. */
    private fun returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse()
        assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
    }

    private fun seedUser(id: Long) {
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id=$id")).isEqualTo("0")
        val inserted = ContractPostgres.admin.update(
            "INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,password,care_reg_no," +
                "provider_id,created_at,updated_at) VALUES(?,?,?,'Auth synthetic','GOOGLE','ROLE_ADMIN','AUTH_PRIVATE'," +
                "'CARE_AUTH',?,TIMESTAMP '2025-01-01 18:01:02.123456',TIMESTAMP '2025-02-01 18:01:02.654321')",
            id,
            "Auth$id",
            "auth-$id@fixture.invalid",
            "auth-provider-$id",
        )
        assertThat(inserted).isOne()
        users.add(id)
    }

    private companion object {
        const val BASE = 9_985_800L
        const val REFRESH_MS = 600_999L
        const val ALLOWED_ORIGIN = "https://contract.example.test"
    }
}
