package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The authenticated member's own profile (replaces the packaged F12 smoke). */
class UserProfileContractTest : ApiContractTestBase() {
    @BeforeEach
    fun seedMembers() {
        seedUser(ME, NICKNAME, "ROLE_SHELTER", "2026-10-19 16:00:00.123456")
        seedUser(OTHER, "F12_OTHER_SYNTHETIC_NICK")
        execute(
            "UPDATE pawbridge_user.users SET name = 'F12_SYNTHETIC_NAME', email = 'f12-synthetic-$ME@fixture.invalid', " +
                "provider = 'LOCAL', password = ?, care_reg_no = ?, provider_id = ? WHERE user_id = ?",
            PRIVATE_VALUES[0], PRIVATE_VALUES[1], PRIVATE_VALUES[2], ME,
        )
    }

    @Test
    fun `거부되는 토큰일 때__프로필을 조회하면__고정된 401 봉투를 주고 아무것도 바뀌지 않는다`() {
        // given
        // when
        // then
        assertNoSideEffects {
            val none = profile(null)
            assertThat(none.text).isEqualTo("""{"code":401,"message":"인증 토큰이 필요합니다.","data":null}""")
            assertThat(none.status).isEqualTo(401)
            for ((name, token) in rejectedTokens(ME)) {
                val reply = profile(token)
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.body.toString()).`as`(name)
                    .isEqualTo("""{"code":401,"message":"${rejectionMessage(name)}","data":null}""")
            }
        }
    }

    @Test
    @Tag("timezone-sensitive")
    fun `모든 역할의 토큰일 때__프로필을 조회하면__주체의 DB 값 일곱 필드만 주고 비공개 열은 없다`() {
        // given
        // when
        // then
        for (role in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER")) {
            val reply = assertNoSideEffects { profile(accessToken(ME, role)) }
            assertThat(reply.status).`as`(role).isEqualTo(200)
            assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
            val data = reply.data
            assertThat(data.fieldNames().asSequence().toSet()).isEqualTo(FIELDS)
            assertThat(data.get("userId").asLong()).isEqualTo(ME)
            assertThat(data.get("email").asText()).isEqualTo("f12-synthetic-$ME@fixture.invalid")
            assertThat(data.get("name").asText()).isEqualTo("F12_SYNTHETIC_NAME")
            assertThat(data.get("nickname").asText()).isEqualTo(NICKNAME)
            assertThat(data.get("provider").asText()).isEqualTo("LOCAL")
            assertThat(data.get("role").asText()).`as`("The stored role, not the token role").isEqualTo("ROLE_SHELTER")
            assertThat(data.get("createdAt").asText()).isEqualTo(shownInJvmZone("2026-10-19 16:00:00.123456"))
            for (private in PRIVATE_VALUES + "1997-02-03") {
                assertThat(reply.text).doesNotContain(private)
            }
        }
    }

    @Test
    fun `쿼리와 본문과 헤더가 다른 회원을 가리킬 때__프로필을 조회하면__토큰 주체의 프로필이고 다른 토큰은 다른 회원이다`() {
        // given
        // when
        val mine = assertNoSideEffects { profile(accessToken(ME), "/api/v1/users/me?userId=$OTHER", """{"userId":$OTHER}""".toByteArray()) }
        val others = assertNoSideEffects { profile(accessToken(OTHER)) }

        // then
        assertThat(mine.data.get("userId").asLong()).isEqualTo(ME)
        assertThat(others.data.get("userId").asLong()).isEqualTo(OTHER)
        assertThat(others.data.get("nickname").asText()).isEqualTo("F12_OTHER_SYNTHETIC_NICK")
    }

    @Test
    @Tag("timezone-sensitive")
    fun `닉네임과 역할과 가입 시각이 바뀌었을 때__프로필을 조회하면__현재 DB 값을 그대로 보여준다`() {
        // given
        val token = accessToken(ME)

        // when
        // then
        for (nickname in listOf("", "  ", NICKNAME)) {
            execute("UPDATE pawbridge_user.users SET nickname = ? WHERE user_id = ?", nickname, ME)
            assertThat(profile(token).data.get("nickname").asText()).`as`("[$nickname]").isEqualTo(nickname)
        }
        for (role in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER")) {
            execute("UPDATE pawbridge_user.users SET role = ?, provider = 'GOOGLE' WHERE user_id = ?", role, ME)
            val data = profile(accessToken(ME, "ROLE_USER")).data
            assertThat(data.get("role").asText()).isEqualTo(role)
            assertThat(data.get("provider").asText()).isEqualTo("GOOGLE")
        }
        execute("UPDATE pawbridge_user.users SET created_at = NULL WHERE user_id = ?", ME)
        val withoutCreation = profile(token).data
        assertThat(withoutCreation.has("createdAt")).`as`("The key stays").isTrue()
        assertThat(withoutCreation.get("createdAt").isNull).isTrue()
        execute("UPDATE pawbridge_user.users SET created_at = '2026-10-19 23:59:59.654321' WHERE user_id = ?", ME)
        assertThat(profile(token).data.get("createdAt").asText()).isEqualTo(shownInJvmZone("2026-10-19 23:59:59.654321"))
    }

    @Test
    fun `회원 행이 없을 때__프로필을 조회하면__400 안내이고 다른 회원에게는 영향이 없다`() {
        // given
        execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", OTHER)

        // when
        val missing = assertNoSideEffects { profile(accessToken(OTHER)) }
        val mine = profile(accessToken(ME))

        // then
        assertThat(missing.status).isEqualTo(400)
        assertThat(missing.text).isEqualTo("""{"code":400,"message":"존재하지 않는 회원입니다.","data":null}""")
        assertThat(mine.status).isEqualTo(200)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("email", "created_at")
    fun `프로필 열 권한이 없을 때__프로필을 조회하면__안전한 500이고 인증이 먼저이며 복구되면 조회된다`(column: String) {
        // given
        val token = accessToken(ME)

        // when
        val (results, logs) = capturingLogs {
            ContractPostgres.withRevoked("SELECT($column)", "pawbridge_user.users") {
                assertNoSideEffects { profile(token) } to assertNoSideEffects { profile(null) }
            }
        }
        val recovered = profile(token)

        // then
        assertThat(results.first.status).isEqualTo(500)
        assertThat(results.first.text).isEqualTo("""{"code":500,"message":"서버 에러!","data":null}""")
        assertThat(results.second.status).isEqualTo(401)
        assertThat(recovered.status).isEqualTo(200)
        for (secret in PRIVATE_VALUES + listOf("F12_SYNTHETIC_NAME", NICKNAME, "f12-synthetic-$ME@fixture.invalid", token,
            ContractPostgres.appPassword, ContractApplicationInitializer.jwtSecret)) {
            assertThat(logs.contains(secret)).`as`("The log must not contain personal fields, tokens or credentials").isFalse()
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "POST, /api/v1/users/me",
        "PUT, /api/v1/users/me",
        "DELETE, /api/v1/users/me",
        "PATCH, /api/v1/users/me",
        "HEAD, /api/v1/users/me",
        "OPTIONS, /api/v1/users/me",
        "GET, /api/v1/users/me/",
        "GET, /api/v1/users/me/extra",
    )
    fun `프로필이 아닌 메서드나 경로일 때__유효한 토큰으로 요청하면__403이고 아무것도 바뀌지 않는다`(method: String, path: String) {
        // given
        val token = accessToken(ME)

        // when
        val reply = assertNoSideEffects { call(method, path, token) }

        // then
        assertThat(reply.status).isEqualTo(403)
    }

    @Test
    fun `게이트웨이에서 옮겨온 경로일 때__요청하면__옛 별칭은 같은 프로필이고 내부 사용자 경로는 404다`() {
        // given
        val token = accessToken(ME)

        // when
        val alias = assertNoSideEffects { profile(token, "/api/users/me") }
        val internal = assertNoSideEffects { get("/api/v1/users/internal/$ME/nickname", token) }

        // then
        assertThat(alias.status).isEqualTo(200)
        assertThat(alias.text).isEqualTo(profile(token).text)
        assertThat(internal.status).isEqualTo(404)
    }

    private fun profile(token: String?, path: String = "/api/v1/users/me", body: ByteArray? = null): Reply =
        call("GET", path, token, FORGED, body)

    private companion object {
        const val ME = 9_912_042L
        const val OTHER = 9_912_043L
        const val NICKNAME = "F12_SYNTHETIC_NICK"
        val PRIVATE_VALUES = listOf(
            "F12_SYNTHETIC_PASSWORD_DO_NOT_EXPOSE", "F12_SYNTHETIC_CARE_DO_NOT_EXPOSE", "F12_SYNTHETIC_PROVIDER_ID_DO_NOT_EXPOSE",
        )
        val FIELDS = setOf("userId", "email", "name", "nickname", "provider", "role", "createdAt")
        val FORGED = mapOf(
            "X-User-Id" to "9980001", "X-User-Email" to "spoofed@fixture.invalid", "X-User-Name" to "spoofed", "X-User-Role" to "ROLE_ADMIN",
        )
    }
}
