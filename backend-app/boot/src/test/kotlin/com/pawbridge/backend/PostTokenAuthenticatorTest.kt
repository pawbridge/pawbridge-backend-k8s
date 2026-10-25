package com.pawbridge.backend

import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Date
import java.util.stream.Stream

class PostTokenAuthenticatorTest {
    private val tokens = PostTokenAuthenticator(F3TestInputs.KEY)

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER", "", " ", "ROLE_UNKNOWN", "ROLE_ADMIN "])
    fun `서명된 접근 신원이 주어졌을 때__인증하면__모든 기존 역할에서 클레임의 사용자 ID를 쓴다`(role: String) {
        // given
        val header = "Bearer " + F3TestInputs.token(7, role)

        // when
        val userId = tokens.authenticate(header)

        // then
        assertThat(userId).isEqualTo(7L)
    }

    @Test
    fun `헤더가 없거나 베어러가 아닐 때__인증하면__토큰 필요 메시지를 유지한다`() {
        // given
        val headers = arrayOf<String?>(null, "Basic ignored", "bearer ignored")

        // when
        val failures = headers.map { header -> assertThatThrownBy { tokens.authenticate(header) } }

        // then
        for (failure in failures) {
            failure
                .isInstanceOf(PostTokenAuthenticator.Failure::class.java)
                .hasMessage("인증 토큰이 필요합니다.")
        }
    }

    @Test
    fun `서명이 잘못됐거나 만료됐을 때__인증하면__토큰 상세를 되풀이하지 않는다`() {
        // given
        val expired = Jwts.builder()
            .claim("userId", 7)
            .claim("role", "ROLE_USER")
            .expiration(Date(1))
            .signWith(F3TestInputs.KEY)
            .compact()
        val rejected = arrayOf("", "private-token-detail", expired, "eyJhbGciOiJub25lIn0.eyJ1c2VySWQiOjd9.")

        // when
        val failures = rejected.map { token -> assertThatThrownBy { tokens.authenticate("Bearer $token") } }

        // then
        for (failure in failures) {
            failure.hasMessage("유효하지 않거나 만료된 토큰입니다.")
        }
    }

    @ParameterizedTest
    @MethodSource("invalidIdentity")
    fun `서명됐지만 잘못된 신원이 주어졌을 때__인증하면__클레임 파싱 실패로 거부한다`(
        claims: Map<String, Any>,
    ) {
        // given
        val token = Jwts.builder()
            .claims(claims)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()

        // when
        val failure = assertThatThrownBy { tokens.authenticate("Bearer $token") }

        // then
        failure.hasMessage("토큰 파싱에 실패했습니다.")
    }

    @Test
    fun `만료 없는 서명 신원이 주어졌을 때__기존 REST 계약으로 인증하면__선택적 만료 정책을 유지한다`() {
        // given
        val token = Jwts.builder()
            .claim("userId", 7)
            .claim("role", "ROLE_UNKNOWN")
            .signWith(F3TestInputs.KEY)
            .compact()

        // when
        val userId = tokens.authenticate("Bearer $token")

        // then
        assertThat(userId).isEqualTo(7L)
    }

    companion object {
        @JvmStatic
        fun invalidIdentity(): Stream<Map<String, Any>> {
            return Stream.of(
                mapOf(),
                mapOf("role" to "ROLE_USER"),
                mapOf("userId" to 0, "role" to "ROLE_USER"),
                mapOf("userId" to -1, "role" to "ROLE_USER"),
                mapOf("userId" to "not-a-number", "role" to "ROLE_USER"),
                mapOf("userId" to 7),
                mapOf("userId" to 7, "role" to mapOf("unexpected" to true)),
            )
        }
    }
}
