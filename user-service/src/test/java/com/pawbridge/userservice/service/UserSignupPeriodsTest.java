package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.response.DailySignupStatsResponse;
import com.pawbridge.userservice.repository.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserSignupPeriodsTest {
    @ParameterizedTest
    @CsvSource({"2026-09-30,2026-10-01", "2026-12-31,2027-01-01"})
    void givenUtcTodayBeforeKoreanMidnight__whenGettingPeriods__thenUseKoreanTodayForEveryPeriod(
            LocalDate serverToday, LocalDate koreanToday) {
        var users = mock(UserRepository.class);
        var service = new UserServiceImpl(users, null, null, null, null);
        var kst = ZoneId.of("Asia/Seoul");
        when(users.countDailySignups(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new DailySignupStatsResponse(koreanToday, 7L)));
        try (var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(serverToday);
            dates.when(() -> LocalDate.now(kst)).thenReturn(koreanToday);
            var response = service.getSignupPeriods();
            var starts = ArgumentCaptor.forClass(LocalDate.class);
            var ends = ArgumentCaptor.forClass(LocalDate.class);
            verify(users, times(4)).countDailySignups(starts.capture(), ends.capture());
            assertThat(starts.getAllValues()).containsExactly(koreanToday, koreanToday.minusDays(6),
                    koreanToday.minusDays(29), koreanToday.withDayOfMonth(1));
            assertThat(ends.getAllValues()).containsExactly(koreanToday, koreanToday, koreanToday, koreanToday);
            assertThat(response.getToday()).containsExactly(new DailySignupStatsResponse(koreanToday, 7L));
            assertThat(response.getLast7Days()).hasSize(7).first()
                    .isEqualTo(new DailySignupStatsResponse(koreanToday.minusDays(6), 0L));
            assertThat(response.getLast30Days()).hasSize(30).last()
                    .isEqualTo(new DailySignupStatsResponse(koreanToday, 7L));
            assertThat(response.getThisMonth()).containsExactly(new DailySignupStatsResponse(koreanToday, 7L));
        }
    }
}
