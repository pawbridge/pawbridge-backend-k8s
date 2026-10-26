package com.pawbridge.backend.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Whether the authenticated member favorited an animal (replaces the packaged F14 smoke). */
class FavoriteStatusContractTest : ApiContractTestBase() {
    @BeforeEach
    fun seedFavorite() {
        seedUser(ME, "F14 합성회원")
        seedUser(OTHER, "F14타인")
        execute(
            "INSERT INTO pawbridge_user.favorites(favorite_id,user_id,animal_id,created_at) VALUES(?,?,?,'2025-01-01 01:02:03.123456')",
            FAVORITE, ME, ANIMAL,
        )
    }

    @Test
    fun `즐겨찾기한 동물일 때__상태를 조회하면__true이고 정확한 봉투이며 아무것도 바뀌지 않는다`() {
        // given
        // when
        val reply = assertNoSideEffects { check(ANIMAL, accessToken(ME)) }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.text).isEqualTo("""{"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":true}""")
    }

    @Test
    fun `즐겨찾기하지 않은 동물이거나 다른 회원일 때__상태를 조회하면__false다`() {
        // given
        // when
        val notFavorited = assertNoSideEffects { check(ANIMAL + 1, accessToken(ME)) }
        val otherMember = assertNoSideEffects { check(ANIMAL, accessToken(OTHER)) }

        // then
        for (reply in listOf(notFavorited, otherMember)) {
            assertThat(reply.status).isEqualTo(200)
            assertThat(reply.text).isEqualTo("""{"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":false}""")
        }
    }

    @Test
    fun `회원 행이 없는 토큰일 때__상태를 조회하면__false다`() {
        // given
        execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", OTHER)

        // when
        val reply = assertNoSideEffects { check(ANIMAL, accessToken(OTHER)) }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.data.isBoolean).isTrue()
        assertThat(reply.data.asBoolean()).isFalse()
    }

    @Test
    fun `토큰이 없거나 만료됐을 때__상태를 조회하면__401이다`() {
        // given
        val expired = rejectedTokens(ME).getValue("expired")

        // when
        val none = assertNoSideEffects { check(ANIMAL, null) }
        val old = assertNoSideEffects { check(ANIMAL, expired) }

        // then
        assertThat(none.status).isEqualTo(401)
        assertThat(none.text).isEqualTo("""{"code":401,"message":"인증 토큰이 필요합니다.","data":null}""")
        assertThat(old.status).isEqualTo(401)
        assertThat(old.text).isEqualTo("""{"code":401,"message":"유효하지 않거나 만료된 토큰입니다.","data":null}""")
    }

    @Test
    fun `동물 식별자가 숫자가 아닐 때__상태를 조회하면__안전한 500이다`() {
        // given
        // when
        val reply = assertNoSideEffects { check("not-a-number", accessToken(ME)) }

        // then
        assertThat(reply.status).isEqualTo(500)
        assertThat(reply.text).isEqualTo("""{"code":500,"message":"서버 에러!","data":null}""")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("user_id", "animal_id")
    fun `즐겨찾기 열 권한이 없을 때__상태를 조회하면__안전한 500이고 복구되면 조회된다`(column: String) {
        // given
        val token = accessToken(ME)

        // when
        val denied = ContractPostgres.withRevoked("SELECT($column)", "pawbridge_user.favorites") {
            assertNoSideEffects { check(ANIMAL, token) }
        }
        val recovered = check(ANIMAL, token)

        // then
        assertThat(denied.status).isEqualTo(500)
        assertThat(denied.text).isEqualTo("""{"code":500,"message":"서버 에러!","data":null}""")
        assertThat(recovered.data.asBoolean()).isTrue()
    }

    /** The path names the animal; the query, body and X-User headers name other ones and must change nothing. */
    private fun check(animal: Any, token: String?): Reply = call(
        "GET",
        "/api/v1/favorites/$animal/check?userId=$OTHER&animalId=${ANIMAL + 1}",
        token,
        mapOf("X-User-Id" to "$OTHER", "X-User-Role" to "ROLE_ADMIN"),
        """{"userId":$OTHER,"animalId":${ANIMAL + 1}}""".toByteArray(),
        "application/json",
    )

    private companion object {
        const val ME = 9_914_042L
        const val OTHER = 9_914_043L
        const val FAVORITE = 9_914_001L
        const val ANIMAL = 9_914_002L
    }
}
