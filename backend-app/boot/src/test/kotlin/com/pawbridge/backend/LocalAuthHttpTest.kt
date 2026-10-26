package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.emailverification.application.EmailSendingException
import com.pawbridge.backend.user.internal.emailverification.application.EmailTemplateLoadException
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.emailverification.application.ExpiredCodeException
import com.pawbridge.backend.user.internal.emailverification.application.InvalidCodeException
import com.pawbridge.backend.user.internal.emailverification.application.TooManyAttemptsException
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordResetCodeInvalidException
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController
import com.pawbridge.backend.user.internal.signup.application.AdminRoleNotAllowedException
import com.pawbridge.backend.user.internal.signup.application.EmailDuplicateException
import com.pawbridge.backend.user.internal.signup.application.EmailNotVerifiedException
import com.pawbridge.backend.user.internal.signup.application.InconsistentPasswordException
import com.pawbridge.backend.user.internal.signup.application.LocalSignup
import com.pawbridge.backend.user.internal.signup.application.RoleRequiredException
import com.pawbridge.backend.user.internal.signup.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.signup.application.SignedUp
import com.pawbridge.backend.user.internal.signup.application.SignupRole
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController
import com.ninjasquad.springmockk.MockkBean
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.stream.Stream

@WebMvcTest(
    controllers = [EmailVerificationController::class, LocalSignupController::class, PasswordResetController::class],
    properties = [
        "pawbridge.email-verification.enabled=true",
        "pawbridge.local-signup.enabled=true",
        "pawbridge.password-reset.enabled=true",
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
class LocalAuthHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var verifications: EmailVerifications

    @MockkBean
    lateinit var signups: LocalSignup

    @MockkBean
    lateinit var resets: PasswordReset

    @Test
    fun `토큰이 없을 때__이메일을 보내고 확인하면__공개 경로가 원래 본문을 돌려준다`() {
        // given
        justRun { verifications.sendVerificationCode(any()) }
        every { verifications.verifyCode(any(), any()) } returns true

        // when
        val sent = mvc.perform(
            post("/api/v1/email/send").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.c\"}"),
        )
        val verified = mvc.perform(
            post("/api/v1/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.c\",\"code\":\"123456\"}"),
        )

        // then
        sent
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"인증 코드가 발송되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verified
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"이메일 인증에 성공했습니다.\",\"data\":{\"verified\":true}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { verifications.sendVerificationCode("a@b.c") }
        verify(exactly = 1) { verifications.verifyCode("a@b.c", "123456") }
        confirmVerified(verifications)
    }

    @Test
    fun `토큰이 없을 때__가입하면__공개 경로가 원본 필드를 넘기고 원래 성공 응답을 돌려준다`() {
        // given
        every { signups.signUp(any(), any(), any(), any(), any(), any()) } returns
            SignedUp(7, "a@b.c", "Kim", "아주귀여운강아지")

        // when
        val result = mvc.perform(
            post("/api/v1/users/signup")
                .header("Authorization", "Bearer ignored")
                .contentType(MediaType.APPLICATION_JSON)
                .content(SIGNUP),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"userId\":7," +
                        "\"email\":\"a@b.c\",\"name\":\"Kim\",\"nickname\":\"아주귀여운강아지\"}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { signups.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null) }
        confirmVerified(signups, verifications, resets)
    }

    @Test
    fun `토큰이 없을 때__비밀번호를 재설정하면__공개 경로가 원래 메시지를 돌려준다`() {
        // given
        justRun { resets.request(any()) }
        justRun { resets.reset(any(), any(), any()) }

        // when
        val requested = mvc.perform(
            post("/api/v1/auth/password/reset-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.c\"}"),
        )
        val reset = mvc.perform(
            post("/api/v1/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.c\",\"code\":\"123456\",\"newPassword\":\"NewPass1!\"}"),
        )

        // then
        requested
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"인증코드가 이메일로 발송되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        reset
            .andExpect(status().isOk())
            .andExpect(
                content().json("{\"code\":200,\"message\":\"비밀번호가 재설정되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { resets.request("a@b.c") }
        verify(exactly = 1) { resets.reset("a@b.c", "123456", "NewPass1!") }
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `서비스가 실패했을 때__공개 경로를 호출하면__원래 상태와 메시지로 매핑한다`(
        path: String,
        body: String,
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        // given
        every { verifications.sendVerificationCode("a@b.c") } throws failure
        every { verifications.verifyCode("a@b.c", "123456") } throws failure
        every { signups.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null) } throws failure
        every { resets.reset("a@b.c", "123456", "NewPass1!") } throws failure

        // when
        val result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

        // then
        result
            .andExpect(status().`is`(status))
            .andExpect(
                content().json(
                    "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    fun `본문이 잘못되었거나 읽을 수 없을 때__공개 경로를 호출하면__서비스 전에 400을 돌려준다`(
        path: String,
        body: String,
        message: String,
    ) {
        // given
        val request = post(path).contentType(MediaType.APPLICATION_JSON).content(body)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json("{\"code\":400,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(verifications, signups, resets)
    }

    companion object {
        private const val SIGNUP = "{\"email\":\"a@b.c\",\"name\":\"Kim\",\"password\":\"pass12\"," +
            "\"rePassword\":\"pass12\",\"role\":\"ROLE_USER\"}"

        @JvmStatic
        fun failures(): Stream<Arguments> {
            val send = "{\"email\":\"a@b.c\"}"
            val verify = "{\"email\":\"a@b.c\",\"code\":\"123456\"}"
            val reset = "{\"email\":\"a@b.c\",\"code\":\"123456\",\"newPassword\":\"NewPass1!\"}"
            return Stream.of(
                Arguments.of("/api/v1/email/verify", verify, ExpiredCodeException(), 400, "인증 코드가 만료되었습니다."),
                Arguments.of("/api/v1/email/verify", verify, InvalidCodeException(), 400, "인증 코드가 일치하지 않습니다."),
                Arguments.of(
                    "/api/v1/email/send",
                    send,
                    TooManyAttemptsException(TooManyAttemptsException.Kind.SEND),
                    429,
                    "인증 코드 발송 횟수를 초과했습니다. 5분 후 다시 시도해주세요.",
                ),
                Arguments.of(
                    "/api/v1/email/verify",
                    verify,
                    TooManyAttemptsException(TooManyAttemptsException.Kind.VERIFY),
                    429,
                    "인증 시도 횟수를 초과했습니다. 새로운 인증 코드를 요청해주세요.",
                ),
                Arguments.of("/api/v1/email/send", send, EmailSendingException(), 500, "이메일 발송에 실패했습니다."),
                Arguments.of("/api/v1/email/send", send, EmailTemplateLoadException(), 500, "이메일 템플릿 로드에 실패했습니다."),
                Arguments.of(
                    "/api/v1/email/send",
                    send,
                    RedisConnectionFailureException("synthetic"),
                    503,
                    "인증 서비스에 일시적인 문제가 발생했습니다. 잠시 후 다시 시도해주세요.",
                ),
                Arguments.of("/api/v1/email/send", send, DataAccessResourceFailureException("synthetic"), 500, "서버 에러!"),
                Arguments.of("/api/v1/email/send", send, IllegalStateException("synthetic"), 500, "서버 에러!"),
                Arguments.of("/api/v1/users/signup", SIGNUP, EmailNotVerifiedException(), 400, "이메일 인증이 필요합니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, EmailDuplicateException(), 409, "이미 사용 중인 이메일입니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, InconsistentPasswordException(), 400, "비밀번호가 일치하지 않습니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, RoleRequiredException(), 400, "회원 유형을 선택해주세요."),
                Arguments.of(
                    "/api/v1/users/signup",
                    SIGNUP,
                    AdminRoleNotAllowedException(),
                    400,
                    "관리자 계정은 회원가입으로 생성할 수 없습니다.",
                ),
                Arguments.of(
                    "/api/v1/users/signup",
                    SIGNUP,
                    ShelterApprovalRequiredException(),
                    400,
                    "보호소 권한은 담당자 신청 승인으로 부여해야 합니다.",
                ),
                Arguments.of(
                    "/api/v1/auth/password/reset",
                    reset,
                    PasswordResetCodeInvalidException(),
                    400,
                    "비밀번호 재설정 인증코드가 유효하지 않습니다.",
                ),
            )
        }

        @JvmStatic
        fun invalidBodies(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("/api/v1/email/send", "{\"email\":\"not-an-email\"}", "email: 올바른 이메일 형식이 아닙니다."),
                Arguments.of(
                    "/api/v1/email/verify",
                    "{\"email\":\"a@b.c\",\"code\":\"12345\"}",
                    "code: 인증 코드는 6자리 숫자여야 합니다.",
                ),
                Arguments.of(
                    "/api/v1/users/signup",
                    SIGNUP.replace("\"ROLE_USER\"", "\"ROLE_OTHER\""),
                    "요청 본문 형식이 올바르지 않습니다.",
                ),
                Arguments.of("/api/v1/users/signup", "{", "요청 본문 형식이 올바르지 않습니다."),
                Arguments.of("/api/v1/auth/password/reset-request", "{\"email\":\"bad\"}", "email: 올바른 이메일 형식이 아닙니다."),
                Arguments.of(
                    "/api/v1/auth/password/reset",
                    "{\"email\":\"a@b.c\",\"code\":\"123456\",\"newPassword\":\"short1!\"}",
                    "newPassword: 비밀번호는 8~20자의 영문, 숫자, 특수문자를 포함해야 합니다.",
                ),
            )
        }
    }
}
