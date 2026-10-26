package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController
import com.pawbridge.backend.user.internal.signup.application.LocalSignup
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController
import com.ninjasquad.springmockk.MockkBean
import io.mockk.confirmVerified
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Controllers are registered directly so only the default-off security routes can block them.
@WebMvcTest(
    controllers = [EmailVerificationController::class, LocalSignupController::class, PasswordResetController::class],
    properties = [
        "pawbridge.email-verification.enabled=on",
        "pawbridge.local-signup.enabled=yes",
        "pawbridge.password-reset.enabled=false",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        EmailVerificationController::class,
        LocalSignupController::class,
        PasswordResetController::class,
        LocalAuthExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class LocalAuthDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var verifications: EmailVerifications

    @MockkBean
    lateinit var signups: LocalSignup

    @MockkBean
    lateinit var resets: PasswordReset

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/v1/email/send", "/api/v1/email/verify", "/api/v1/users/signup",
            "/api/v1/auth/password/reset-request", "/api/v1/auth/password/reset",
        ],
    )
    fun `플래그가 정확히 true가 아닐 때__공개 경로를 호출하면__서비스 전에 거절한다`(path: String) {
        // given
        val request = post(path).contentType(MediaType.APPLICATION_JSON).content("{}")

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(verifications, signups, resets)
    }
}
