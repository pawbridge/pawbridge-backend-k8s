package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword
import com.pawbridge.backend.user.internal.password.web.UserPasswordController
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler
import io.mockk.confirmVerified
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// The controller is registered directly so only the default-off security route can block it.
@WebMvcTest(
    controllers = [UserPasswordController::class],
    properties = ["pawbridge.user-password.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        UserPasswordController::class,
        UserPasswordExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserPasswordDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var change: ChangeUserPassword

    @Test
    fun `플래그가 정확히 true가 아닐 때__유효한 토큰으로 비밀번호를 바꾸면__서비스 전에 경로가 거부된다`() {
        // given
        val request = put("/api/v1/users/me/password")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"Current1!\",\"newPassword\":\"NewPass1!\"}")

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(change)
    }
}
