package com.pawbridge.backend;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.emailverification.application.EmailSendingException;
import com.pawbridge.backend.user.internal.emailverification.application.EmailTemplateLoadException;
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.emailverification.application.ExpiredCodeException;
import com.pawbridge.backend.user.internal.emailverification.application.InvalidCodeException;
import com.pawbridge.backend.user.internal.emailverification.application.TooManyAttemptsException;
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController;
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordResetCodeInvalidException;
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController;
import com.pawbridge.backend.user.internal.signup.application.AdminRoleNotAllowedException;
import com.pawbridge.backend.user.internal.signup.application.EmailDuplicateException;
import com.pawbridge.backend.user.internal.signup.application.EmailNotVerifiedException;
import com.pawbridge.backend.user.internal.signup.application.InconsistentPasswordException;
import com.pawbridge.backend.user.internal.signup.application.LocalSignup;
import com.pawbridge.backend.user.internal.signup.application.RoleRequiredException;
import com.pawbridge.backend.user.internal.signup.application.ShelterApprovalRequiredException;
import com.pawbridge.backend.user.internal.signup.application.SignedUp;
import com.pawbridge.backend.user.internal.signup.application.SignupRole;
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {EmailVerificationController.class, LocalSignupController.class, PasswordResetController.class},
        properties = {"pawbridge.email-verification.enabled=true", "pawbridge.local-signup.enabled=true",
                "pawbridge.password-reset.enabled=true"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {EmailVerificationController.class, LocalSignupController.class,
        PasswordResetController.class, LocalAuthExceptionHandler.class, ReadSecurityConfiguration.class,
        F3TestInputs.SecurityInputs.class})
class LocalAuthHttpTest {
    private static final String SIGNUP = "{\"email\":\"a@b.c\",\"name\":\"Kim\",\"password\":\"pass12\","
            + "\"rePassword\":\"pass12\",\"role\":\"ROLE_USER\"}";
    @Autowired MockMvc mvc;
    @MockitoBean EmailVerifications verifications;
    @MockitoBean LocalSignup signups;
    @MockitoBean PasswordReset resets;

    @Test
    void given__no_token__when__sending_and_verifying_email__then__public_routes_return_original_bodies() throws Exception {
        when(verifications.verifyCode("a@b.c", "123456")).thenReturn(true);

        mvc.perform(post("/api/v1/email/send").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"인증 코드가 발송되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(post("/api/v1/email/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"이메일 인증에 성공했습니다.\",\"data\":{\"verified\":true}}",
                        JsonCompareMode.STRICT));
        verify(verifications).sendVerificationCode("a@b.c");
        verify(verifications).verifyCode("a@b.c", "123456");
        verifyNoMoreInteractions(verifications);
    }

    @Test
    void given__no_token__when__signing_up__then__public_route_forwards_raw_fields_and_returns_original_success() throws Exception {
        when(signups.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null))
                .thenReturn(new SignedUp(7, "a@b.c", "Kim", "아주귀여운강아지"));

        mvc.perform(post("/api/v1/users/signup").header("Authorization", "Bearer ignored")
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"userId\":7,"
                        + "\"email\":\"a@b.c\",\"name\":\"Kim\",\"nickname\":\"아주귀여운강아지\"}}", JsonCompareMode.STRICT));
        verifyNoInteractions(verifications, resets);
    }

    @Test
    void given__no_token__when__resetting_password__then__public_routes_return_original_messages() throws Exception {
        mvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"인증코드가 이메일로 발송되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\",\"code\":\"123456\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"비밀번호가 재설정되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(resets).request("a@b.c");
        verify(resets).reset("a@b.c", "123456", "NewPass1!");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void given__service_failure__when__calling_a_public_route__then__map_to_original_status_and_message(String path,
            String body, RuntimeException failure, int status, String message) throws Exception {
        doThrow(failure).when(verifications).sendVerificationCode("a@b.c");
        doThrow(failure).when(verifications).verifyCode("a@b.c", "123456");
        when(signups.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null)).thenThrow(failure);
        doThrow(failure).when(resets).reset("a@b.c", "123456", "NewPass1!");

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andExpect(content().json("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));
    }

    static Stream<Arguments> failures() {
        String send = "{\"email\":\"a@b.c\"}";
        String verify = "{\"email\":\"a@b.c\",\"code\":\"123456\"}";
        String reset = "{\"email\":\"a@b.c\",\"code\":\"123456\",\"newPassword\":\"NewPass1!\"}";
        return Stream.of(
                Arguments.of("/api/v1/email/verify", verify, new ExpiredCodeException(), 400, "인증 코드가 만료되었습니다."),
                Arguments.of("/api/v1/email/verify", verify, new InvalidCodeException(), 400, "인증 코드가 일치하지 않습니다."),
                Arguments.of("/api/v1/email/send", send, new TooManyAttemptsException(TooManyAttemptsException.Kind.SEND), 429,
                        "인증 코드 발송 횟수를 초과했습니다. 5분 후 다시 시도해주세요."),
                Arguments.of("/api/v1/email/verify", verify, new TooManyAttemptsException(TooManyAttemptsException.Kind.VERIFY), 429,
                        "인증 시도 횟수를 초과했습니다. 새로운 인증 코드를 요청해주세요."),
                Arguments.of("/api/v1/email/send", send, new EmailSendingException(), 500, "이메일 발송에 실패했습니다."),
                Arguments.of("/api/v1/email/send", send, new EmailTemplateLoadException(), 500, "이메일 템플릿 로드에 실패했습니다."),
                Arguments.of("/api/v1/email/send", send, new RedisConnectionFailureException("synthetic"), 503,
                        "인증 서비스에 일시적인 문제가 발생했습니다. 잠시 후 다시 시도해주세요."),
                Arguments.of("/api/v1/email/send", send, new DataAccessResourceFailureException("synthetic"), 500, "서버 에러!"),
                Arguments.of("/api/v1/email/send", send, new IllegalStateException("synthetic"), 500, "서버 에러!"),
                Arguments.of("/api/v1/users/signup", SIGNUP, new EmailNotVerifiedException(), 400, "이메일 인증이 필요합니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, new EmailDuplicateException(), 409, "이미 사용 중인 이메일입니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, new InconsistentPasswordException(), 400, "비밀번호가 일치하지 않습니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, new RoleRequiredException(), 400, "회원 유형을 선택해주세요."),
                Arguments.of("/api/v1/users/signup", SIGNUP, new AdminRoleNotAllowedException(), 400, "관리자 계정은 회원가입으로 생성할 수 없습니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP, new ShelterApprovalRequiredException(), 400,
                        "보호소 권한은 담당자 신청 승인으로 부여해야 합니다."),
                Arguments.of("/api/v1/auth/password/reset", reset, new PasswordResetCodeInvalidException(), 400,
                        "비밀번호 재설정 인증코드가 유효하지 않습니다."));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void given__invalid_or_unreadable_body__when__calling_a_public_route__then__400_before_service(String path, String body,
            String message) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(verifications, signups, resets);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("/api/v1/email/send", "{\"email\":\"not-an-email\"}", "email: 올바른 이메일 형식이 아닙니다."),
                Arguments.of("/api/v1/email/verify", "{\"email\":\"a@b.c\",\"code\":\"12345\"}", "code: 인증 코드는 6자리 숫자여야 합니다."),
                Arguments.of("/api/v1/users/signup", SIGNUP.replace("\"ROLE_USER\"", "\"ROLE_OTHER\""), "요청 본문 형식이 올바르지 않습니다."),
                Arguments.of("/api/v1/users/signup", "{", "요청 본문 형식이 올바르지 않습니다."),
                Arguments.of("/api/v1/auth/password/reset-request", "{\"email\":\"bad\"}", "email: 올바른 이메일 형식이 아닙니다."),
                Arguments.of("/api/v1/auth/password/reset",
                        "{\"email\":\"a@b.c\",\"code\":\"123456\",\"newPassword\":\"short1!\"}",
                        "newPassword: 비밀번호는 8~20자의 영문, 숫자, 특수문자를 포함해야 합니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/email/send", "PUT /api/v1/email/send", "POST /api/v1/email/send/",
            "POST /api/v1/email/other", "POST /api/email/send", "GET /api/v1/users/signup", "POST /api/v1/users/signup/",
            "POST /api/users/signup", "GET /api/v1/auth/password/reset", "POST /api/v1/auth/password/reset/",
            "POST /api/v1/auth/password/other", "POST /api/auth/password/reset"})
    void given__method_or_path_outside_the_original_whitelist__when__requesting__then__deny_without_service(String request)
            throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(verifications, signups, resets);
    }
}
