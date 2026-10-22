package com.pawbridge.backend;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController;
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset;
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController;
import com.pawbridge.backend.user.internal.signup.application.LocalSignup;
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Controllers are registered directly so only the default-off security routes can block them.
@WebMvcTest(controllers = {EmailVerificationController.class, LocalSignupController.class, PasswordResetController.class},
        properties = {"pawbridge.email-verification.enabled=on", "pawbridge.local-signup.enabled=yes",
                "pawbridge.password-reset.enabled=false"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {EmailVerificationController.class, LocalSignupController.class,
        PasswordResetController.class, LocalAuthExceptionHandler.class, ReadSecurityConfiguration.class,
        F3TestInputs.SecurityInputs.class})
class LocalAuthDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmailVerifications verifications;
    @MockitoBean LocalSignup signups;
    @MockitoBean PasswordReset resets;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/email/send", "/api/v1/email/verify", "/api/v1/users/signup",
            "/api/v1/auth/password/reset-request", "/api/v1/auth/password/reset"})
    void given__flag_not_exactly_true__when__calling_a_public_route__then__deny_before_service(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(verifications, signups, resets);
    }
}
