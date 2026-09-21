package com.pawbridge.userservice.persistence;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.service.NicknameGeneratorService;
import com.pawbridge.userservice.service.UserServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserRollbackCharsetServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final EmailVerificationService email = mock(EmailVerificationService.class);
    private final NicknameGeneratorService nicknames = mock(NicknameGeneratorService.class);
    private final UserServiceImpl service = new UserServiceImpl(users, passwords, email, nicknames, mock(AnimalServiceClient.class));
    private final DataIntegrityViolationException failure = new DataIntegrityViolationException("charset",
            PostgresqlRollbackCharsetViolationTest.error("23514", "ck_rollback_charset_users", "pawbridge_user"));

    @Test
    void invalid_signup_is_not_retried_as_a_nickname_collision_or_cleared_from_verification() {
        when(email.isVerified("test@example.invalid")).thenReturn(true);
        when(nicknames.generateUniqueNickname()).thenReturn("검증닉네임");
        when(passwords.encode("password")).thenReturn("hash");
        when(users.save(any(User.class))).thenThrow(failure);
        SignUpRequestDto input = new SignUpRequestDto("test@example.invalid", "🐕", "password", "password", Role.ROLE_USER, null);
        assertThatThrownBy(() -> service.signUp(input)).isSameAs(failure);
        verify(users, times(1)).save(any(User.class));
        verify(nicknames, times(1)).generateUniqueNickname();
        verify(email, never()).clearVerification(anyString());
    }

    @Test
    void invalid_nickname_retains_charset_error_instead_of_duplicate_nickname_message() {
        User user = mock(User.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(user.getNickname()).thenReturn("기존");
        when(users.save(user)).thenThrow(failure);
        assertThatThrownBy(() -> service.updateNickname(1L, new UpdateNicknameRequestDto("🐕"))).isSameAs(failure);
    }
}
