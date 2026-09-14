package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.request.*;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.PasswordResetCodeInvalidException;
import com.pawbridge.userservice.exception.NicknameDuplicateException;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.RefreshTokenRepository;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.support.TestLogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserLogPrivacyTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmailVerificationService verification = mock(EmailVerificationService.class);
    private final NicknameGeneratorService nicknames = mock(NicknameGeneratorService.class);
    private final UserServiceImpl service = new UserServiceImpl(users, encoder, verification,
            nicknames, mock(AnimalServiceClient.class));
    private final AuthServiceImpl auth = new AuthServiceImpl(mock(RefreshTokenRepository.class),
            users, mock(JwtProvider.class), encoder, verification);
    private static final String EMAIL = "private-person@example.test";
    private static final String NICKNAME = "PrivateNick";
    private static final String PASSWORD = "Synthetic-password-123!";
    private static final String CODE = "synthetic-verification-code";

    private User createUser() {
        return User.builder().userId(7L).email(EMAIL).name("PrivateName")
                .nickname(NICKNAME).provider("LOCAL").role(Role.ROLE_USER).build();
    }

    @Test
    void givenVerifiedSignup_whenSaved_thenIdentityIsReturnedButNotLogged() {
        var request = new SignUpRequestDto(EMAIL, "PrivateName", PASSWORD, PASSWORD, Role.ROLE_USER, null);
        when(verification.isVerified(EMAIL)).thenReturn(true);
        when(nicknames.generateUniqueNickname()).thenReturn(NICKNAME);
        when(encoder.encode(PASSWORD)).thenReturn("encoded-test-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException(EMAIL)).when(verification).clearVerification(EMAIL);

        try (var logs = new TestLogCapture(UserServiceImpl.class)) {
            var result = service.signUp(request);
            assertThat(result).isNotNull();
            var saved = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(users).save(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getValue().getNickname()).isEqualTo(NICKNAME);
            assertThat(saved.getValue().getPassword()).isEqualTo("encoded-test-password");
            assertThat(logs.text()).contains("회원가입 완료")
                    .doesNotContain(EMAIL, NICKNAME, PASSWORD, "PrivateName");
        }
    }

    @Test
    void givenAdminUpdate_whenApplied_thenNicknameChangesWithoutLoggingRequest() {
        var user = createUser();
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        var request = new AdminUserUpdateRequest("ChangedNick", Role.ROLE_USER, null);

        try (var logs = new TestLogCapture(UserServiceImpl.class)) {
            service.updateUserByAdmin(7L, request);
            assertThat(user.getNickname()).isEqualTo(request.nickname());
            verify(users).save(user);
            assertThat(logs.text()).contains("userId=7")
                    .doesNotContain(request.toString(), request.nickname(), EMAIL);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenResetEmail_whenSendSucceedsOrFails_thenResponseAndPrivacyArePreserved(boolean fail) {
        when(users.findByEmailAndProvider(EMAIL, "LOCAL")).thenReturn(Optional.of(createUser()));
        if (fail) {
            doThrow(new IllegalStateException("recipient=" + EMAIL)).when(verification).sendPasswordResetCode(EMAIL);
        }
        try (var logs = new TestLogCapture(AuthServiceImpl.class)) {
            assertThatCode(() -> auth.requestPasswordReset(new PasswordResetRequestDto(EMAIL)))
                    .doesNotThrowAnyException();
            verify(verification).sendPasswordResetCode(EMAIL);
            assertThat(logs.text()).isNotBlank().doesNotContain(EMAIL);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "changed", "duplicate"})
    void givenNicknameUpdate_whenProcessed_thenResultIsPreservedWithoutLoggingNickname(String scenario) {
        var user = createUser();
        String requested = scenario.equals("same") ? NICKNAME : "ChangedNick";
        when(users.findById(7L)).thenReturn(Optional.of(user));
        if (scenario.equals("duplicate")) {
            when(users.save(user)).thenThrow(new DataIntegrityViolationException(requested));
        }
        try (var logs = new TestLogCapture(UserServiceImpl.class)) {
            var request = new UpdateNicknameRequestDto(requested);
            if (scenario.equals("duplicate")) {
                assertThatThrownBy(() -> service.updateNickname(7L, request))
                        .isInstanceOf(NicknameDuplicateException.class);
            } else {
                service.updateNickname(7L, request);
                assertThat(user.getNickname()).isEqualTo(requested);
            }
            if (scenario.equals("same")) verify(users, never()).save(any());
            else verify(users).save(user);
            assertThat(logs.text()).isNotBlank().doesNotContain(NICKNAME, requested, EMAIL);
        }
    }

    @Test
    void givenVerifiedReset_whenSaved_thenPasswordChangesWithoutLoggingIdentityOrCode() {
        var user = createUser();
        when(users.findByEmailAndProvider(EMAIL, "LOCAL")).thenReturn(Optional.of(user));
        when(verification.verifyPasswordResetCode(EMAIL, CODE)).thenReturn(true);
        when(encoder.encode(PASSWORD)).thenReturn("encoded-test-password");
        doThrow(new IllegalStateException(EMAIL)).when(verification).clearPasswordResetVerification(EMAIL);

        try (var logs = new TestLogCapture(AuthServiceImpl.class)) {
            auth.resetPassword(new PasswordResetVerifyDto(EMAIL, CODE, PASSWORD));
            verify(users).save(user);
            assertThat(user.getPassword()).isEqualTo("encoded-test-password");
            assertThat(logs.text()).contains("비밀번호 재설정 완료")
                    .doesNotContain(EMAIL, CODE, PASSWORD, "encoded-test-password");
        }
    }

    @Test
    void givenVerificationFailure_whenResetRequested_thenErrorIsPreservedWithoutLeakingCause() {
        when(users.findByEmailAndProvider(EMAIL, "LOCAL")).thenReturn(Optional.of(createUser()));
        when(verification.verifyPasswordResetCode(EMAIL, CODE))
                .thenThrow(new IllegalStateException(EMAIL + " " + CODE));
        try (var logs = new TestLogCapture(AuthServiceImpl.class)) {
            assertThatThrownBy(() -> auth.resetPassword(new PasswordResetVerifyDto(EMAIL, CODE, PASSWORD)))
                    .isInstanceOf(PasswordResetCodeInvalidException.class);
            verify(users, never()).save(any());
            assertThat(logs.text()).isNotBlank().doesNotContain(EMAIL, CODE, PASSWORD);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenNicknameAvailability_whenGenerated_thenReturnedNicknameIsNotLogged(boolean allTaken) {
        when(users.existsByNickname(any())).thenReturn(allTaken);
        var generator = new NicknameGeneratorService(users);
        try (var logs = new TestLogCapture(NicknameGeneratorService.class)) {
            String nickname = generator.generateUniqueNickname();
            assertThat(nickname).isNotBlank();
            assertThat(logs.text()).isNotBlank().doesNotContain(nickname);
        }
    }
}
