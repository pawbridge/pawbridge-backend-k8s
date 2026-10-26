package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote
import com.pawbridge.backend.community.internal.note.web.NoteSendController
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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

    @MockkBean
    lateinit var sending: SendPrivateNote

    @ParameterizedTest
    @ValueSource(strings = ["USER", "EXPIRYLESS_ADMIN"])
    fun `서명된 REST 호출자와 위조 헤더가 주어졌을 때__쪽지를 보내면__검증된 신원과 여섯 입력을 전달한다`(kind: String) {
        // given
        val token = if (kind == "USER") {
            F3TestInputs.token(7, "ROLE_USER")
        } else {
            Jwts.builder().claim("userId", 7L).claim("role", "ROLE_ADMIN").signWith(F3TestInputs.KEY).compact()
        }

        every { sending.send(any(), any()) } returns SendPrivateNote.Receipt(NOTE)

        // when
        val result = mvc.perform(
            post("/api/v1/notes")
                .header("Authorization", "Bearer $token")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"noteId\":\"" + NOTE + "\"}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { sending.send(7L, SendNote(8L, " raw body ", REQUEST, null, null, null)) }
        confirmVerified(sending)
        cleared()
    }

    @Test
    fun `검증된 호출자의 본문이 깨졌을 때__쪽지를 보내면__유스케이스 호출 없이 범위 한정 400을 돌려준다`() {
        // given
        val token = F3TestInputs.token(7, "ROLE_USER")

        // when
        val result = mvc.perform(
            post("/api/v1/notes")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{malformed"),
        )

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        confirmVerified(sending)
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
