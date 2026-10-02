package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.dto.response.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPostStatsServiceTest {
    private final PostRepository posts = mock(PostRepository.class);
    private final LocalDate day = LocalDate.of(2026, 10, 2);
    private final AdminPostStatsService service = new AdminPostStatsService(posts,
            Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC));

    @Test void givenRange__whenAggregated__thenSeparatePreviousDayAndSelectedPeriodTypes() {
        when(posts.countDailyPosts(day.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new DailyPostStats(day.minusDays(1), 3), new DailyPostStats(day, 4)));
        when(posts.countByBoardType(day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new BoardTypeStats(BoardType.ADOPTION, 4)));
        var result = service.period(day, day);
        assertThat(result.daily()).containsExactly(new DailyPostStats(day, 4));
        assertThat(result.previousDayCount()).isEqualTo(3);
        assertThat(result.byBoardType()).containsExactly(new BoardTypeStats(BoardType.ADOPTION, 4));
        verify(posts).countDailyPosts(day.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay());
        verify(posts).countByBoardType(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }

    @Test void givenInvalidRange__whenRequestingPeriod__thenRejectBeforeQuery() {
        for (LocalDate[] range : new LocalDate[][]{{null, day}, {day, null}, {day, day.minusDays(1)},
                {day.minusDays(366), day}, {day, day.plusDays(1)}}) {
            assertThatThrownBy(() -> service.period(range[0], range[1])).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(posts);
    }

    @Test void given366DayRangeWithNoPosts__whenRequestingPeriod__thenReturnSuccessfulEmptySeries() {
        var start = day.minusDays(365);
        when(posts.countDailyPosts(start.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(List.of());
        when(posts.countByBoardType(start.atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(List.of());
        var result = service.period(start, day);
        assertThat(result.daily()).isEmpty();
        assertThat(result.previousDayCount()).isZero();
        assertThat(result.byBoardType()).isEmpty();
    }

    @Test void givenDatabaseFailure__whenRequestingPeriod__thenDoNotReportZero() {
        when(posts.countDailyPosts(any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.period(day, day)).isInstanceOf(IllegalStateException.class);
    }
}
