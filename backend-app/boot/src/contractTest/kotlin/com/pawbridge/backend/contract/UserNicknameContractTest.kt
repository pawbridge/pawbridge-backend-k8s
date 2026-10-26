package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Changing the authenticated member's nickname (replaces the packaged F13 smoke). */
class UserNicknameContractTest : ApiContractTestBase() {
    @BeforeEach
    fun seedMembers() {
        seedUser(ME, "F12_SYNTHETIC_NICK")
        seedUser(OTHER, "F13타인")
        seedPost(POST, ME)
        seedComment(COMMENT, POST, ME, "F13 합성댓글")
    }

    @Test
    fun `새 닉네임일 때__변경하면__닉네임과 수정 시각만 바뀌고 프로필과 댓글 작성자에 바로 보인다`() {
        // given
        val before = ContractPostgres.snapshot()
        val storage = storageState()

        // when
        val reply = put("F13변경")
        val after = ContractPostgres.snapshot()

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.text).isEqualTo("""{"code":200,"message":"닉네임이 변경되었습니다.","data":null}""")
        val previous = row("pawbridge_user.users", "user_id", ME)
        assertThat(previous.get("nickname").asText()).isEqualTo("F13변경")
        assertThat(previous.get("updated_at").asText()).isNotEqualTo("1997-02-03T04:05:06.654321")
        assertThat(after.filterKeys { it != "pawbridge_user.users" }).isEqualTo(before.filterKeys { it != "pawbridge_user.users" })
        val rowsBefore = MAPPER.readTree(before.getValue("pawbridge_user.users")).associateBy { it.get("user_id").asLong() }
        val rowsAfter = MAPPER.readTree(after.getValue("pawbridge_user.users")).associateBy { it.get("user_id").asLong() }
        assertThat(rowsAfter.getValue(OTHER)).`as`("The spoofed userId/X-User-Id changed nobody").isEqualTo(rowsBefore.getValue(OTHER))
        assertThat(omitting(rowsAfter.getValue(ME), "nickname", "updated_at"))
            .isEqualTo(omitting(rowsBefore.getValue(ME), "nickname", "updated_at"))
        assertThat(storageState()).isEqualTo(storage)
        assertVisibleEverywhere("F13변경")
    }

    @Test
    fun `이미 같은 닉네임일 때__다시 변경하면__성공하지만 어떤 행도 쓰지 않는다`() {
        // given
        assertThat(put("F13변경").status).isEqualTo(200)

        // when
        val before = ContractPostgres.snapshot()
        val reply = put("F13변경")

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(ContractPostgres.snapshot() == before).`as`("A same-nickname PUT writes nothing").isTrue()
        assertVisibleEverywhere("F13변경")
    }

    @Test
    fun `대소문자만 다르거나 다른 회원의 닉네임일 때__변경하면__409이고 아무것도 바뀌지 않는다`() {
        // given
        assertThat(put("F13변경").status).isEqualTo(200)

        // when
        // then
        assertNoSideEffects {
            for (taken in listOf("f13변경", "F13타인")) {
                val reply = put(taken)
                assertThat(reply.status).`as`(taken).isEqualTo(409)
                assertThat(reply.code).isEqualTo(409)
                assertThat(reply.message).isEqualTo("이미 사용 중인 닉네임입니다.")
                assertThat(reply.data.isNull).isTrue()
            }
        }
    }

    @Test
    fun `형식이 틀린 닉네임이거나 본문이 깨졌을 때__변경하면__400이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        // then
        assertNoSideEffects {
            for (invalid in listOf("", " ", "a", "123", "F13_불가", "이모지😀")) {
                val reply = put(invalid)
                assertThat(reply.status).`as`("[$invalid]").isEqualTo(400)
                assertThat(reply.message).contains("nickname:")
                assertThat(reply.data.isNull).isTrue()
            }
            val broken = put(raw = "{")
            assertThat(broken.status).isEqualTo(400)
            assertThat(broken.message).isEqualTo("요청 본문 형식이 올바르지 않습니다.")
        }
    }

    @Test
    fun `거부되는 토큰일 때__변경하면__401이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        // then
        assertNoSideEffects {
            val none = put("F13변경", token = null)
            assertThat(none.status).isEqualTo(401)
            assertThat(none.message).isEqualTo("인증 토큰이 필요합니다.")
            for ((name, token) in rejectedTokens(ME)) {
                val reply = put("F13변경", token = token)
                assertThat(reply.status).`as`(name).isEqualTo(401)
                assertThat(reply.message).isEqualTo(rejectionMessage(name))
            }
        }
    }

    @Test
    fun `회원 행이 없을 때__변경하면__400 안내이고 아무것도 바뀌지 않는다`() {
        // given
        execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", OTHER)

        // when
        val reply = assertNoSideEffects { put("F13변경", token = accessToken(OTHER)) }

        // then
        assertThat(reply.status).isEqualTo(400)
        assertThat(reply.message).isEqualTo("존재하지 않는 회원입니다.")
    }

    @Test
    fun `쓰기 열 권한이 하나 없을 때__변경하면__같은 값은 성공하고 다른 값은 안전한 500이며 복구되면 변경된다`() {
        // given
        assertThat(put("F13변경").status).isEqualTo(200)

        // when
        // then
        for (column in listOf("nickname", "updated_at")) {
            val (denied, logs) = capturingLogs {
                ContractPostgres.withRevoked("UPDATE($column)", "pawbridge_user.users") {
                    val same = put("F13변경")
                    val different = assertNoSideEffects { put("F13회복") }
                    val unauthenticated = assertNoSideEffects { put("F13회복", token = null) }
                    Triple(same, different, unauthenticated)
                }
            }
            assertThat(denied.first.status).`as`("$column same value").isEqualTo(200)
            assertThat(denied.second.status).`as`("$column new value").isEqualTo(500)
            assertThat(denied.second.message).isEqualTo("서버 에러!")
            assertThat(denied.third.status).isEqualTo(401)
            assertThat(denied.third.message).isEqualTo("인증 토큰이 필요합니다.")
            for (secret in listOf("F13변경", "F13회복", "F13타인", "contract-$ME@fixture.invalid", accessToken(ME),
                ContractPostgres.appPassword, ContractApplicationInitializer.jwtSecret)) {
                assertThat(logs.contains(secret)).`as`("The log must not contain nicknames, emails, tokens or credentials").isFalse()
            }
            assertVisibleEverywhere("F13변경")
        }
        assertThat(put("F13회복").status).isEqualTo(200)
        assertVisibleEverywhere("F13회복")
    }

    @Test
    fun `관리자나 보호소 토큰일 때__변경하면__자기 닉네임이 바뀐다`() {
        // given
        // when
        val admin = put("F13회복", token = accessToken(ME, "ROLE_ADMIN"))
        val adminVisible = visibleNickname()
        val shelter = put("F13보호소", token = accessToken(ME, "ROLE_SHELTER"))

        // then
        assertThat(admin.status).isEqualTo(200)
        assertThat(adminVisible).isEqualTo("F13회복")
        assertThat(shelter.status).isEqualTo(200)
        assertVisibleEverywhere("F13보호소")
    }

    private fun put(nickname: String? = null, token: String? = accessToken(ME), raw: String? = null): Reply {
        val body = raw ?: MAPPER.writeValueAsString(mapOf("nickname" to nickname, "userId" to OTHER))
        return putJson("/api/v1/users/me/nickname?userId=$OTHER", body, token, mapOf("X-User-Id" to "$OTHER", "X-User-Role" to "ROLE_ADMIN"))
    }

    private fun visibleNickname(): String = get("/api/v1/users/me", accessToken(ME)).data.get("nickname").asText()

    /** The profile and the author of an existing comment both show the committed nickname, without any write. */
    private fun assertVisibleEverywhere(nickname: String) {
        val before = ContractPostgres.snapshot()
        val profile = get("/api/v1/users/me", accessToken(ME))
        val comments = get("/api/v1/comments/posts/read/$POST")
        assertThat(profile.data.get("userId").asLong()).isEqualTo(ME)
        assertThat(profile.data.get("nickname").asText()).isEqualTo(nickname)
        assertThat(comments.data.any { it.get("commentId").asLong() == COMMENT && it.get("authorNickname").asText() == nickname }).isTrue()
        assertThat(ContractPostgres.snapshot() == before).isTrue()
        assertNoTransactionOpen()
    }

    private companion object {
        const val ME = 9_913_042L
        const val OTHER = 9_913_043L
        const val POST = 9_913_001L
        const val COMMENT = 9_913_002L
    }
}
