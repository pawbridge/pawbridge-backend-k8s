package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.request.*;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.entity.*;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.service.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ShelterSignupGuardTest {
    final UserRepository users = mock(UserRepository.class);
    final EmailVerificationService email = mock(EmailVerificationService.class);
    final AnimalServiceClient animals = mock(AnimalServiceClient.class);
    final UserServiceImpl service = new UserServiceImpl(users, mock(PasswordEncoder.class), email,
            mock(NicknameGeneratorService.class), animals);
    @Test void verifiedEmailCannotBypassApprovalThroughSignup() {
        when(email.isVerified("member@example.invalid")).thenReturn(true);
        for (Role role : new Role[]{Role.ROLE_USER, Role.ROLE_SHELTER}) {
            var request = new SignUpRequestDto("member@example.invalid", "member", "password", "password", role, "123");
            assertThatThrownBy(() -> service.signUp(request)).isInstanceOf(ShelterApplicationException.class);
        }
        verify(users, never()).save(any());
        verifyNoInteractions(animals);
    }
    @Test void adminMemberEditorCannotGrantShelterRole() {
        User user = User.builder().userId(10L).role(Role.ROLE_USER).build();
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.updateUserByAdmin(10L, new AdminUserUpdateRequest(null, Role.ROLE_SHELTER, "123")))
                .isInstanceOf(ShelterApplicationException.class);
        assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
        verify(users, never()).save(any());
    }
    @Test void existingShelterCanRetainItsRegistration() {
        User user = User.builder().userId(10L).role(Role.ROLE_SHELTER).careRegNo("123").build();
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        service.updateUserByAdmin(10L, new AdminUserUpdateRequest(null, Role.ROLE_SHELTER, "123"));
        assertThat(user.getRole()).isEqualTo(Role.ROLE_SHELTER);
        assertThat(user.getCareRegNo()).isEqualTo("123");
        verify(users).save(user);
    }
    @Test void existingShelterCannotChangeRegistrationThroughMemberEditor() {
        User user = User.builder().userId(10L).role(Role.ROLE_SHELTER).careRegNo("123").build();
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.updateUserByAdmin(10L, new AdminUserUpdateRequest(null, null, "456")))
                .isInstanceOf(ShelterApplicationException.class);
        assertThat(user.getCareRegNo()).isEqualTo("123");
        verify(users, never()).save(any());
    }
}
