package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShelterApplicationStatsServiceTest {
    private final ShelterApplicationRepository applications = mock(ShelterApplicationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtProvider jwt = new JwtProvider("test-only-secret-with-at-least-thirty-two-characters", 60000, 120000);
    private final User admin = User.builder().userId(1L).email("admin@example.invalid").role(Role.ROLE_ADMIN).build();
    private final String auth = "Bearer " + jwt.createAccessToken(admin);
    private final LocalDate day = LocalDate.of(2026, 10, 2);
    private final ShelterApplicationService service = new ShelterApplicationService(applications, users,
            mock(AnimalServiceClient.class), jwt, mock(EntityManager.class),
            Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC));

    private void permitAdmin() { when(users.findById(1L)).thenReturn(Optional.of(admin)); }

    @Test void givenRange__whenAggregated__thenSeparateRequestsReviewsAndCurrentPending() {
        permitAdmin();
        when(applications.countDailyRequests(day.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new DailyShelterApplicationStats(day.minusDays(1), 3), new DailyShelterApplicationStats(day, 5)));
        when(applications.countReviews(day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new ShelterReviewStats(ShelterApplicationStatus.APPROVED, 7), new ShelterReviewStats(ShelterApplicationStatus.REJECTED, 2)));
        when(applications.countByStatus(ShelterApplicationStatus.PENDING)).thenReturn(11L);
        var result = service.statistics(auth, day, day);
        assertThat(result.daily()).containsExactly(new DailyShelterApplicationStats(day, 5));
        assertThat(result.previousDayCount()).isEqualTo(3);
        assertThat(result.currentPending()).isEqualTo(11);
        assertThat(result.approvedCount()).isEqualTo(7);
        assertThat(result.rejectedCount()).isEqualTo(2);
        verify(applications).countDailyRequests(day.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay());
        verify(applications).countReviews(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }

    @Test void givenRevokedAdministrator__whenRequestingStatistics__thenDenyBeforeAggregate() {
        admin.updateRole(Role.ROLE_USER);
        permitAdmin();
        assertThatThrownBy(() -> service.statistics(auth, day, day)).isInstanceOf(ShelterApplicationException.class);
        verifyNoInteractions(applications);
    }

    @Test void givenMissingTokenOrUser__whenRequestingStatistics__thenDenyBeforeAggregate() {
        assertThatThrownBy(() -> service.statistics(null, day, day)).isInstanceOf(ShelterApplicationException.class);
        assertThatThrownBy(() -> service.statistics(auth, day, day)).isInstanceOf(ShelterApplicationException.class);
        verifyNoInteractions(applications);
    }

    @Test void givenInvalidRange__whenRequestingStatistics__thenRejectBeforeAggregate() {
        permitAdmin();
        for (LocalDate[] range : new LocalDate[][]{{null, day}, {day, null}, {day, day.minusDays(1)},
                {day.minusDays(366), day}, {day, day.plusDays(1)}}) {
            assertThatThrownBy(() -> service.statistics(auth, range[0], range[1])).isInstanceOf(ShelterApplicationException.class);
        }
        verifyNoInteractions(applications);
    }

    @Test void given366DaysWithEmptyData__whenRequestingStatistics__thenReturnZeroWithoutExtraPublicDay() {
        permitAdmin();
        var start = day.minusDays(365);
        when(applications.countDailyRequests(start.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(List.of());
        when(applications.countReviews(start.atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(List.of());
        var result = service.statistics(auth, start, day);
        assertThat(result.daily()).isEmpty();
        assertThat(result.previousDayCount()).isZero();
        assertThat(result.approvedCount()).isZero();
        assertThat(result.rejectedCount()).isZero();
    }

    @Test void givenDatabaseFailure__whenRequestingStatistics__thenDoNotReturnZeroSuccess() {
        permitAdmin();
        when(applications.countDailyRequests(any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.statistics(auth, day, day)).isInstanceOf(IllegalStateException.class);
    }
}
