package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.stream.Stream

@WebMvcTest(
    controllers = [UserNicknameController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        UserNicknameController::class,
        UserNicknameExceptionHandler::class,
        UserProfileController::class,
        UserProfileExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserNicknameHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var change: ChangeUserNickname

    @MockitoBean
    lateinit var profiles: UserProfileQuery

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__verified_principal_and_spoofed_identity__when__changing_nickname__then__use_only_principal_and_return_original_null_success(
        role: String,
    ) {
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임7\",\"userId\":999}"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"닉네임이 변경되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(change).change(7, "새닉네임7")
        verifyNoMoreInteractions(change)
        verifyNoInteractions(profiles, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["가1", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcd"])
    fun given__valid_two_or_thirty_character_nickname__when__changing__then__forward_original_text(nickname: String) {
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("nickname" to nickname))),
        )
            .andExpect(status().isOk())
        verify(change).change(7, nickname)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__json_boolean_nickname__when__changing__then__preserve_existing_jackson_string_coercion(
        nickname: Boolean,
    ) {
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":$nickname}"),
        )
            .andExpect(status().isOk())
        verify(change).change(7, nickname.toString())
    }

    @ParameterizedTest
    @MethodSource("invalidNicknames")
    fun given__invalid_or_absent_nickname__when__changing__then__validation_precedes_service_and_keeps_original_message_set(
        json: String,
        messages: List<String>,
    ) {
        val result = mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andReturn()
        val response = mapper.readTree(result.response.contentAsString)
        val message = response.get("message").asText()
        assertThat(message).startsWith("nickname: ")
        assertThat(message.substring("nickname: ".length).split(", nickname: "))
            .containsExactlyInAnyOrderElementsOf(messages)
        assertThat(response.get("data").isNull).isTrue()
        verifyNoInteractions(change, profiles, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{", "null", "[]", "{\"nickname\":[]}", "{\"nickname\":{}}"])
    fun given__malformed_or_nonobject_json__when__changing__then__return_original_safe_body_error_before_service(
        json: String,
    ) {
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
        )
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(change, profiles, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_untrusted_token_and_invalid_body__when__changing__then__authentication_precedes_validation_and_service(
        authorization: String?,
        message: String,
    ) {
        val request = put("/api/v1/users/me/nickname")
            .header("X-User-Id", "7")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json(
                    mapper.writeValueAsString(UserNicknameController.Response<Any?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(change, profiles, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    fun given__signed_invalid_identity__when__changing__then__claim_failure_prevents_service(
        claims: Map<String, Any>,
    ) {
        val token = Jwts.builder()
            .claims(claims)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("토큰 파싱에 실패했습니다."))
        verifyNoInteractions(change, profiles, readPost, listPosts)
    }

    @Test
    fun given__authenticated_member_no_longer_exists__when__changing__then__preserve_missing_member_response() {
        doThrow(UserNotFoundException()).`when`(change).change(7, "새닉네임")
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임\"}"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(change).change(7, "새닉네임")
    }

    @Test
    fun given__nickname_already_used__when__changing__then__return_original_conflict_without_success_fallback() {
        doThrow(NicknameDuplicateException()).`when`(change).change(7, "기존닉네임")
        mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"기존닉네임\"}"),
        )
            .andExpect(status().isConflict())
            .andExpect(
                content().json(
                    "{\"code\":409,\"message\":\"이미 사용 중인 닉네임입니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(change).change(7, "기존닉네임")
    }

    @Test
    fun given__existing_profile_and_public_read__when__requesting__then__nickname_advice_and_authentication_do_not_intercept_them() {
        whenever(profiles.require(7))
            .thenReturn(UserProfile(7, "synthetic@fixture.invalid", "합성", "기존닉네임", "LOCAL", "ROLE_USER", null))
        whenever(listPosts.read()).thenReturn(listOf())
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nickname").value("기존닉네임"))
        mvc.perform(get("/api/v1/posts/read").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
        verifyNoInteractions(change)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/users/me/nickname",
            "POST /api/v1/users/me/nickname",
            "PATCH /api/v1/users/me/nickname",
            "DELETE /api/v1/users/me/nickname",
            "HEAD /api/v1/users/me/nickname",
            "OPTIONS /api/v1/users/me/nickname",
            "PUT /api/v1/users/me/nickname/",
            "PUT /api/v1/users/me/nickname/extra",
            "PUT /api/v1/users/7/nickname",
            "PUT /api/v1/users/me/password",
            "PUT /api/users/me/nickname",
        ],
    )
    fun given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_service(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(change, profiles, readPost, listPosts)
    }

    companion object {
        private const val REQUIRED = "닉네임은 필수입니다."
        private const val SIZE = "닉네임은 2~30자여야 합니다."
        private const val PATTERN =
            "닉네임은 한글, 영문, 숫자를 사용할 수 있으며 띄워쓰기가 불가능합니다. 한글 또는 영문을 최소 1자 포함해야 합니다."

        @JvmStatic
        fun invalidNicknames(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("{}", listOf(REQUIRED)),
                Arguments.of("{\"nickname\":null}", listOf(REQUIRED)),
                Arguments.of("{\"nickname\":\"\"}", listOf(REQUIRED, SIZE, PATTERN)),
                Arguments.of("{\"nickname\":\"A\"}", listOf(SIZE)),
                Arguments.of("{\"nickname\":\"" + "A".repeat(31) + "\"}", listOf(SIZE)),
                Arguments.of("{\"nickname\":\"닉 이름\"}", listOf(PATTERN)),
                Arguments.of("{\"nickname\":\"닉😀\"}", listOf(PATTERN)),
                Arguments.of("{\"nickname\":42}", listOf(PATTERN)),
            )
        }

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            val expired = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(1))
                .signWith(F3TestInputs.KEY)
                .compact()
            val otherKey = Keys.hmacShaKeyFor(
                "f13-synthetic-other-signing-key-0123456789012345678901234567890123456789"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            val forged = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER").signWith(otherKey).compact()
            val refresh = Jwts.builder()
                .expiration(Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $expired", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $forged", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $refresh", "토큰 파싱에 실패했습니다."),
            )
        }
    }
}
