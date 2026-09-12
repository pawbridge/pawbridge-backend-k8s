package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.response.ShelterResponse;
import com.pawbridge.userservice.entity.*;
import com.pawbridge.userservice.exception.common.*;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ShelterApplicationServiceTest {
    final ShelterApplicationRepository applications = mock(ShelterApplicationRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final AnimalServiceClient animals = mock(AnimalServiceClient.class);
    final JwtProvider jwt = new JwtProvider("test-only-secret-with-at-least-thirty-two-characters", 60000, 120000);
    final EntityManager em = mock(EntityManager.class);
    final ShelterApplicationService service = new ShelterApplicationService(applications, users, animals, jwt, em);
    User user, admin;
    String userAuth, adminAuth;
    @BeforeEach void setup() {
        user = User.builder().userId(10L).email("user@example.invalid").name("사용자").role(Role.ROLE_USER).build();
        admin = User.builder().userId(1L).email("admin@example.invalid").name("관리자").role(Role.ROLE_ADMIN).build();
        userAuth = "Bearer " + jwt.createAccessToken(user);
        adminAuth = "Bearer " + jwt.createAccessToken(admin);
    }
    void adminAndApplication(ShelterApplication a) {
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(applications.findById(5L)).thenReturn(Optional.of(a));
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(users.findById(10L)).thenReturn(Optional.of(user));
    }
    @Test void requestUsesAuthenticatedIdentityAndPreservesRole() {
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(applications.save(any())).thenAnswer(i -> i.getArgument(0));
        var result = service.submit(userAuth, "보호소");
        assertThat(result.userId()).isEqualTo(10L);
        assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
        verify(users).findByIdForUpdate(10L);
    }
    @Test void duplicatePendingIsRejected() {
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(applications.existsByUserIdAndStatus(10L, ShelterApplicationStatus.PENDING)).thenReturn(true);
        assertThatThrownBy(() -> service.submit(userAuth, "다른 보호소")).isInstanceOf(ApplicationException.class);
        verify(applications, never()).save(any());
    }
    @Test void rejectedHistoryDoesNotPreventNewRequest() {
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(applications.existsByUserIdAndStatus(10L, ShelterApplicationStatus.PENDING)).thenReturn(false);
        when(applications.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.submit(userAuth, "재신청 보호소").status()).isEqualTo(ShelterApplicationStatus.PENDING);
    }
    @Test void existingShelterCannotRequestAgain() {
        user.updateRole(Role.ROLE_SHELTER);
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.submit(userAuth, "다른 보호소")).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(applications);
    }
    @Test void missingForgedAndRefreshTokensCannotSubmit() {
        for (String token : new String[]{null, "Bearer forged", "Bearer " + jwt.createRefreshToken()}) {
            assertThatThrownBy(() -> service.submit(token, "보호소")).isInstanceOf(ApplicationException.class);
        }
        verifyNoInteractions(users, applications);
    }
    @Test void expiredTokenCannotSubmit() {
        var expired = new JwtProvider("test-only-secret-with-at-least-thirty-two-characters", -10000, 120000);
        assertThatThrownBy(() -> service.submit("Bearer " + expired.createAccessToken(user), "보호소")).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(users, applications);
    }
    @Test void nonAdminCannotApproveOrReadRequests() {
        when(users.findById(10L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.approve(userAuth, 5L, "123", "확인")).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> service.detail(userAuth, 5L)).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> service.list(userAuth, null, 0, 20)).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(applications, animals);
    }
    @Test void removedAdminPrivilegeIsRespectedDespiteOldToken() {
        admin.updateRole(Role.ROLE_USER);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.reject(adminAuth, 5L, "반려")).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(applications);
    }
    @Test void approvalGrantsSameMemberValidatedShelter() {
        var a = ShelterApplication.request(10L, "보호소");
        adminAndApplication(a);
        when(animals.getShelterByCareRegNo("123")).thenReturn(ShelterResponse.builder().id(2L).careRegNo("123").build());
        service.approve(adminAuth, 5L, "123", "공식 연락처로 담당 확인");
        assertThat(user.getUserId()).isEqualTo(10L);
        assertThat(user.getRole()).isEqualTo(Role.ROLE_SHELTER);
        assertThat(user.getCareRegNo()).isEqualTo("123");
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.APPROVED);
        verify(em).refresh(a, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }
    @Test void shelterOutageDoesNotGrantOrApprove() {
        var a = ShelterApplication.request(10L, "보호소");
        adminAndApplication(a);
        when(animals.getShelterByCareRegNo("123")).thenThrow(new RuntimeException("unavailable"));
        assertThatThrownBy(() -> service.approve(adminAuth, 5L, "123", "확인")).isInstanceOf(ApplicationException.class);
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.PENDING);
        assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(user.getCareRegNo()).isNull();
    }
    @Test void mismatchedShelterResponseDoesNotGrant() {
        var a = ShelterApplication.request(10L, "보호소");
        adminAndApplication(a);
        when(animals.getShelterByCareRegNo("123")).thenReturn(ShelterResponse.builder().id(2L).careRegNo("999").build());
        assertThatThrownBy(() -> service.approve(adminAuth, 5L, "123", "확인")).isInstanceOf(ApplicationException.class);
        assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
    }
    @Test void rejectionKeepsRoleAndDoesNotCallShelterService() {
        var a = ShelterApplication.request(10L, "보호소");
        adminAndApplication(a);
        service.reject(adminAuth, 5L, "확인 불가");
        assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.REJECTED);
        verifyNoInteractions(animals);
    }
    @Test void refreshedConcurrentDecisionStopsSecondDecision() {
        var a = ShelterApplication.request(10L, "보호소");
        adminAndApplication(a);
        doAnswer(i -> { a.reject(2L, "이미 처리"); return null; }).when(em).refresh(a, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> service.approve(adminAuth, 5L, "123", "확인")).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(animals);
        assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
    }
    @Test void mineOnlyQueriesAuthenticatedMember() {
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(applications.findByUserId(eq(10L), any())).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.mine(userAuth, 0, 20).getContent()).isEmpty();
        verify(applications).findByUserId(eq(10L), any());
    }
    @Test void oversizedAdminListIsRejected() {
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.list(adminAuth, null, 0, 101)).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(applications);
    }
}
