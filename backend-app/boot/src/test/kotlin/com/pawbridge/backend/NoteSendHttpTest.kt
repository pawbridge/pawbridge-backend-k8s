package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote
import com.pawbridge.backend.community.internal.note.web.NoteSendController
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler
import com.pawbridge.backend.support.anyNonNull
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Real MVC/JWT boundary only; the mock executes no SQL, commit or SSE. */
@WebMvcTest(
    controllers = [NoteSendController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteSendController::class,
        NoteSendExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteSendHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var sending: SendPrivateNote

    @ParameterizedTest
    @ValueSource(strings = ["USER", "EXPIRYLESS_ADMIN"])
    fun givenSignedRestCallerAndSpoofedHeaders__whenSending__thenBindVerifiedIdentityAndPreserveSixInputs(kind: String) {
        whenever(sending.send(eq(7L), anyNonNull<SendNote>())).thenReturn(SendPrivateNote.Receipt(NOTE))
        val token = if (kind == "USER") {
            F3TestInputs.token(7, "ROLE_USER")
        } else {
            Jwts.builder().claim("userId", 7L).claim("role", "ROLE_ADMIN").signWith(F3TestInputs.KEY).compact()
        }
        mvc.perform(
            post("/api/v1/notes")
                .header("Authorization", "Bearer $token")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"noteId\":\"" + NOTE + "\"}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(sending).send(7L, SendNote(8L, " raw body ", REQUEST, null, null, null))
        verifyNoMoreInteractions(sending)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID"])
    fun givenNoValidTokenAndMalformedBody__whenSending__thenAuthenticateBeforeBindingWithoutCallingTheUseCase(kind: String) {
        val request = post("/api/v1/notes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{malformed")
            .header("X-User-Id", "7")
        if (kind == "INVALID") {
            request.header("Authorization", "Bearer invalid")
        }
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(401))
        verifyNoInteractions(sending)
        cleared()
    }

    @Test
    fun givenVerifiedCallerAndMalformedBody__whenSending__thenUseScoped400WithoutInvokingTheUseCase() {
        mvc.perform(
            post("/api/v1/notes")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{malformed"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        verifyNoInteractions(sending)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/notes", "/api/v1/notes/unexposed/send"])
    fun givenAdjacentUnexposedPost__whenUsingAValidToken__thenKeepDenyAllWithoutCallingTheUseCase(path: String) {
        mvc.perform(
            post(path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(sending)
        cleared()
    }

    companion object {
        private val NOTE: UUID = UUID.fromString("f2900000-0000-0000-0000-000000000001")
        private val REQUEST: UUID = UUID.fromString("f2900000-0000-0000-0000-000000000002")
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"
        private val BODY = "{\"recipientId\":8,\"body\":\" raw body \",\"requestId\":\"" + REQUEST +
            "\",\"replyTo\":null,\"contextType\":null,\"contextId\":null}"

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
