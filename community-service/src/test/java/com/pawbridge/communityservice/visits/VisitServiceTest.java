package com.pawbridge.communityservice.visits;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class VisitServiceTest {
    final VisitRepository repository=mock(VisitRepository.class);
    final VisitCookie cookies=new VisitCookie(VisitCookieTest.KEY);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final Instant now=Instant.parse("2026-10-12T01:00:00Z");
    VisitService service;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new VisitService(repository,cookies,Clock.fixed(now,ZoneOffset.UTC),manager);
    }
    @Test void givenMissingCookie_whenCount_thenConflictWithoutStorage() {
        assertThatThrownBy(() -> service.count(null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
    @Test void givenValidCookie_whenCount_thenHashOnlyAndCommit() {
        String value=cookies.initialize(null,now).getValue();
        when(repository.today(LocalDate.of(2026,10,12))).thenReturn(7L);
        assertThat(service.count(value).count()).isEqualTo(7);
        verify(repository).count(eq(LocalDate.of(2026,10,12)),matches("[0-9a-f]{64}"));
        verify(manager).commit(any());
    }
    @Test void givenRequestWaitsAcrossKoreanMidnight_whenDayLockAcquired_thenRejectAndRollback() {
        Clock changing=mock(Clock.class);
        Instant before=Instant.parse("2026-10-12T14:59:59Z");
        when(changing.instant()).thenReturn(before, before.plusSeconds(2));
        var midnight=new VisitService(repository,cookies,changing,manager);
        assertThatThrownBy(() -> midnight.count(cookies.initialize(null,before).getValue()))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository).lockDay(LocalDate.of(2026,10,12),false);
        verify(repository,never()).count(any(),any());
        verify(manager).rollback(any());
    }
    @Test void givenStartedMidPeriod_whenPeriod_thenOmitUncollectedDatesAndFillCollectedZero() {
        when(repository.startedAt()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"));
        when(repository.period(any(),any())).thenReturn(List.of(new VisitModels.Day(LocalDate.of(2026,10,12),2)));
        var result=service.period(LocalDate.of(2026,10,1),LocalDate.of(2026,10,12));
        assertThat(result.daily()).containsExactly(
                new VisitModels.Day(LocalDate.of(2026,10,10),0),
                new VisitModels.Day(LocalDate.of(2026,10,11),0),
                new VisitModels.Day(LocalDate.of(2026,10,12),2));
    }
    @Test void givenFutureReversedOrExcessiveRange_whenPeriod_thenRejectBeforeQueries() {
        assertThatThrownBy(() -> service.period(LocalDate.of(2026,10,12),LocalDate.of(2026,10,13))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.period(LocalDate.of(2026,10,12),LocalDate.of(2026,10,11))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.period(LocalDate.of(2025,10,11),LocalDate.of(2026,10,12))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
    @Test void givenMidnightGrace_whenCleanup_thenWaitUntilFiveMinutesAndKeepAggregates() {
        var grace=new VisitService(repository,cookies,Clock.fixed(Instant.parse("2026-10-11T15:04:59Z"),ZoneOffset.UTC),manager);
        grace.cleanup();verifyNoInteractions(repository);
        when(repository.expiredDays(LocalDate.of(2026,10,12))).thenReturn(List.of(LocalDate.of(2026,10,11)));
        service.cleanup();
        verify(repository).lockDay(LocalDate.of(2026,10,11),true);
        verify(repository).deleteDay(LocalDate.of(2026,10,11));
        verify(repository,never()).count(any(),any());
    }
}
