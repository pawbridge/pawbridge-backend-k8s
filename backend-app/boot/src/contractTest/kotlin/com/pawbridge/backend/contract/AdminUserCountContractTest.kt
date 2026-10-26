package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `GET /api/v1/admin/users/stats/total-users` on the real application and PostgreSQL: the exact member count for an
 * administrator only, the safe error when the count query fails, and no write on any path.
 */
class AdminUserCountContractTest : NoteContractBase() {
    override val ids = 9_973_000L..9_973_999L

    @BeforeEach
    fun seedMember() {
        seedUser(PLAIN_USER, "F19member")
    }

    private fun total(): Long = scalar("SELECT count(*) FROM pawbridge_user.users")!!.toLong()

    /** Count endpoints are answered by the Spring Security chain, so success carries its cache header too. */
    private fun assertCount(reply: Reply, expected: Long) {
        assertThat(reply.status).`as`(reply.text).isEqualTo(200)
        assertThat(reply.header("Set-Cookie")).isNull()
        assertThat(reply.header("Cache-Control")).isEqualTo(SECURITY_CACHE_CONTROL)
        assertThat(reply.json).isEqualTo(obj("code" to 200, "message" to SUCCESS, "data" to expected))
        assertThat(reply.json.get("data").isIntegralNumber).isTrue()
    }

    @Test
    fun `관리자일 때__전체 회원 수를 조회하면__실제 행 수를 정수로 준다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")

        // when
        val reply = withoutWrites { get(PATH, admin) }

        // then
        assertCount(reply, total())
        assertThat(total()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `관리자가 아닐 때__전체 회원 수를 조회하면__관리자 권한 403으로 거부한다`() {
        // given
        val member = token(PLAIN_USER)

        // when
        val reply = withoutWrites { get(PATH, member) }

        // then
        assertSecurity(reply, 403, "관리자 권한이 필요합니다.")
    }

    @Test
    fun `회원 id 열을 읽을 수 없을 때__전체 회원 수를 조회하면__안전한 500이고 복구하면 다시 센다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")
        val member = token(PLAIN_USER)

        // when
        val (failed, stillDenied) = ContractPostgres.withRevoked("SELECT(user_id)", "pawbridge_user.users") {
            withoutWrites { get(PATH, admin) } to withoutWrites { get(PATH, member) }
        }
        val recovered = withoutWrites { get(PATH, admin) }

        // then
        assertSecurity(failed, 500, "서버 에러!")
        assertSecurity(stillDenied, 403, "관리자 권한이 필요합니다.")
        assertCount(recovered, total())
    }

    @Test
    fun `토큰이 없거나 이웃 경로일 때__호출하면__401과 403으로 거부한다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")

        // when
        val missing = withoutWrites { get(PATH, null) }
        val neighbour = withoutWrites { get("/api/v1/admin/users/stats/daily-signups/extra", admin) }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(neighbour, 403, "접근할 수 없습니다.")
    }

    private companion object {
        const val PATH = "/api/v1/admin/users/stats/total-users"
        const val ADMIN = 9_973_001L
        const val PLAIN_USER = 9_973_002L
    }
}
