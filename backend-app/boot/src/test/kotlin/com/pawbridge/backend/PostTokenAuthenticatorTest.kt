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
    fun given__signed_access_identity__when__authenticating__then__use_claim_user_id_for_every_existing_role(role: String) {
        assertThat(tokens.authenticate("Bearer " + F3TestInputs.token(7, role))).isEqualTo(7L)
    }

    @Test
    fun given__missing_or_non_bearer_header__when__authenticating__then__keep_missing_token_message() {
        for (header in arrayOf<String?>(null, "Basic ignored", "bearer ignored")) {
            assertThatThrownBy { tokens.authenticate(header) }
                .isInstanceOf(PostTokenAuthenticator.Failure::class.java)
                .hasMessage("인증 토큰이 필요합니다.")
        }
    }

    @Test
    fun given__invalid_or_expired_signature__when__authenticating__then__do_not_echo_token_details() {
        val expired = Jwts.builder()
            .claim("userId", 7)
            .claim("role", "ROLE_USER")
            .expiration(Date(1))
            .signWith(F3TestInputs.KEY)
            .compact()
        for (token in arrayOf("", "private-token-detail", expired, "eyJhbGciOiJub25lIn0.eyJ1c2VySWQiOjd9.")) {
            assertThatThrownBy { tokens.authenticate("Bearer $token") }
                .hasMessage("유효하지 않거나 만료된 토큰입니다.")
        }
    }

    @ParameterizedTest
    @MethodSource("invalidIdentity")
    fun given__signed_but_invalid_identity__when__authenticating__then__reject_as_claim_parsing_failure(
        claims: Map<String, Any>,
    ) {
        val token = Jwts.builder()
            .claims(claims)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()
        assertThatThrownBy { tokens.authenticate("Bearer $token") }.hasMessage("토큰 파싱에 실패했습니다.")
    }

    @Test
    fun givenSignedIdentityWithoutExpiry__whenAuthenticatingAnExistingRestContract__thenKeepItsOptionalExpiryPolicy() {
        val token = Jwts.builder()
            .claim("userId", 7)
            .claim("role", "ROLE_UNKNOWN")
            .signWith(F3TestInputs.KEY)
            .compact()
        assertThat(tokens.authenticate("Bearer $token")).isEqualTo(7L)
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
