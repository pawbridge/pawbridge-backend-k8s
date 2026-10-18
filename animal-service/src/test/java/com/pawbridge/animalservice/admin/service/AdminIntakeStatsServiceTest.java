package com.pawbridge.animalservice.admin.service;

import com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse;
import com.pawbridge.animalservice.admin.repository.AdminStatsRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminIntakeStatsServiceTest {
    private final AdminStatsRepository repository = mock(AdminStatsRepository.class);
    private final LocalDate today = LocalDate.of(2026, 10, 2);
    private final AdminStatsServiceImpl service = new AdminStatsServiceImpl(repository,
            Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC));

    @Test void givenPreviousDay__whenReadingTrend__thenSeparateComparisonFromSelectedPeriod() {
        LocalDate start = today.minusDays(2);
        when(repository.countDailyIntakes(start.minusDays(1), today)).thenReturn(List.of(
                new DailyAnimalStatsResponse(start.minusDays(1), 7L),
                new DailyAnimalStatsResponse(start, 2L), new DailyAnimalStatsResponse(today, 4L)));
        var result = service.getIntakeTrend(start, today);
        assertThat(result.previousDayCount()).isEqualTo(7);
        assertThat(result.daily()).extracting(DailyAnimalStatsResponse::getDate).containsExactly(start, today);
        verify(repository).countDailyIntakes(start.minusDays(1), today);
        verifyNoMoreInteractions(repository);
    }

    @Test void givenNoRecords__whenReadingTrend__thenReturnSuccessfulEmptySeriesAndZeroComparison() {
        when(repository.countDailyIntakes(today.minusDays(1), today)).thenReturn(List.of());
        var result = service.getIntakeTrend(today, today);
        assertThat(result.daily()).isEmpty();
        assertThat(result.previousDayCount()).isZero();
    }

    @Test void given366Days__whenReadingTrend__thenAllowInternalPreviousDayWithoutExpandingPublicRange() {
        LocalDate start = today.minusDays(365);
        when(repository.countDailyIntakes(start.minusDays(1), today)).thenReturn(List.of());
        assertThat(service.getIntakeTrend(start, today).startDate()).isEqualTo(start);
    }

    @Test void givenInvalidRanges__whenReadingTrend__thenRejectBeforeQuery() {
        for (LocalDate[] range : List.of(new LocalDate[]{null, today}, new LocalDate[]{today, null},
                new LocalDate[]{today, today.minusDays(1)}, new LocalDate[]{today.minusDays(366), today},
                new LocalDate[]{today, today.plusDays(1)})) {
            assertThatThrownBy(() -> service.getIntakeTrend(range[0], range[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test void givenStorageFailure__whenReadingTrend__thenDoNotReturnZeroSeries() {
        when(repository.countDailyIntakes(today.minusDays(1), today)).thenThrow(new IllegalStateException("fixture"));
        assertThatThrownBy(() -> service.getIntakeTrend(today, today)).isInstanceOf(IllegalStateException.class);
    }
}
