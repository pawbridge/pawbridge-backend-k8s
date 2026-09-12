package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.repository.AnimalStatsRepository;
import com.pawbridge.animalservice.enums.AnimalStatus;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnimalStatsServiceTest {
    private final AnimalStatsRepository repository = mock(AnimalStatsRepository.class);
    private AnimalStatsServiceImpl service(String instant) {
        return new AnimalStatsServiceImpl(repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
    @Test
    void givenUtcPreviousDay__whenReadingToday__thenUseKoreanDate() {
        var result = service("2026-09-11T15:00:00Z").getTodayStats();
        assertThat(result.getDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        verify(repository).countRescuedToday(LocalDate.of(2026, 9, 12));
        verify(repository).countAdoptedToday(LocalDate.of(2026, 9, 12), AnimalStatus.ADOPTED);
    }
    @Test
    void givenBeforeKoreanMidnight__whenReadingToday__thenKeepPreviousDate() {
        assertThat(service("2026-09-11T14:59:59Z").getTodayStats().getDate())
            .isEqualTo(LocalDate.of(2026, 9, 11));
    }
    @Test
    void givenNoDates__whenReadingStatusAndRegion__thenUseSameInclusiveThirtyDays() {
        var service = service("2026-09-11T15:00:00Z");
        service.getStatusStats(null, null);
        service.getRegionalStats(null, null);
        verify(repository).countByStatus(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 9, 12));
        verify(repository).countByShelterForRegional(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 9, 12));
    }
    @Test
    void givenExplicitEnd__whenDefaultingStart__thenIncludeLeapDay() {
        service("2026-09-11T15:00:00Z").getStatusStats(null, LocalDate.of(2024, 3, 1));
        verify(repository).countByStatus(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1));
    }
    @Test
    void givenCustomDates__whenReadingStatus__thenPreserveRequestedBoundaries() {
        service("2026-09-11T15:00:00Z").getStatusStats(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        verify(repository).countByStatus(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    }
}
