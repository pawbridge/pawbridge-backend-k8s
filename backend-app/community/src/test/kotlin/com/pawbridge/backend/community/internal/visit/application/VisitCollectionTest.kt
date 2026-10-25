package com.pawbridge.backend.community.internal.visit.application

import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollection
import com.pawbridge.backend.community.support.anyNonNull
import com.pawbridge.backend.community.support.eqNonNull
import com.pawbridge.backend.community.support.matchesNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class VisitCollectionTest {
    private val repository = mock(VisitCollectionStore::class.java)
    private val cookies = VisitIdentity(VisitIdentityTest.KEY)
    private val manager = mock(PlatformTransactionManager::class.java)
    private val now = Instant.parse("2026-10-12T01:00:00Z")
    private lateinit var service: VisitCollection

    @BeforeEach
    fun setup() {
        whenever(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        service = JdbcVisitCollection(repository, cookies, Clock.fixed(now, ZoneOffset.UTC), TransactionTemplate(manager))
    }

    @Test
    fun givenMissingCookie_whenCount_thenConflictWithoutStorage() {
        assertThatThrownBy { service.count(null) }.isInstanceOf(VisitCollectionRejectedException::class.java)
        verifyNoInteractions(repository)
    }

    @Test
    fun givenValidCookie_whenCount_thenHashOnlyAndCommit() {
        val value = cookies.initialize(null, now)!!.value
        whenever(repository.today(LocalDate.of(2026, 10, 12))).thenReturn(7L)
        assertThat(service.count(value).count).isEqualTo(7)
        val order = inOrder(repository, manager)
        order.verify(repository).lockDay(LocalDate.of(2026, 10, 12), false)
        order.verify(repository).count(eqNonNull(LocalDate.of(2026, 10, 12)), matchesNonNull("[0-9a-f]{64}"))
        order.verify(repository).today(LocalDate.of(2026, 10, 12))
        order.verify(manager).commit(any())
        verify(manager).commit(any())
    }

    @Test
    fun givenRequestWaitsAcrossKoreanMidnight_whenDayLockAcquired_thenRejectAndRollback() {
        val changing = mock(Clock::class.java)
        val before = Instant.parse("2026-10-12T14:59:59Z")
        whenever(changing.instant()).thenReturn(before, before.plusSeconds(2))
        val midnight = JdbcVisitCollection(repository, cookies, changing, TransactionTemplate(manager))
        assertThatThrownBy { midnight.count(cookies.initialize(null, before)!!.value) }
            .isInstanceOf(VisitCollectionRejectedException::class.java)
        verify(repository).lockDay(LocalDate.of(2026, 10, 12), false)
        verify(repository, never()).count(anyNonNull(), anyNonNull())
        verify(manager).rollback(any())
    }

    @Test
    fun givenStartedMidPeriod_whenPeriod_thenOmitUncollectedDatesAndFillCollectedZero() {
        whenever(repository.startedAt()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"))
        whenever(repository.period(anyNonNull(), anyNonNull()))
            .thenReturn(listOf(VisitCollection.Day(LocalDate.of(2026, 10, 12), 2)))
        val result = service.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 12))
        assertThat(result.daily).containsExactly(
            VisitCollection.Day(LocalDate.of(2026, 10, 10), 0),
            VisitCollection.Day(LocalDate.of(2026, 10, 11), 0),
            VisitCollection.Day(LocalDate.of(2026, 10, 12), 2),
        )
    }

    @Test
    fun givenFutureReversedOrExcessiveRange_whenPeriod_thenRejectBeforeQueries() {
        assertThatThrownBy { service.period(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)) }
            .isInstanceOf(VisitCollectionRejectedException::class.java)
        assertThatThrownBy { service.period(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11)) }
            .isInstanceOf(VisitCollectionRejectedException::class.java)
        assertThatThrownBy { service.period(LocalDate.of(2025, 10, 11), LocalDate.of(2026, 10, 12)) }
            .isInstanceOf(VisitCollectionRejectedException::class.java)
        verifyNoInteractions(repository)
    }

    @Test
    fun givenMidnightGrace_whenCleanup_thenWaitUntilFiveMinutesAndKeepAggregates() {
        val grace = JdbcVisitCollection(
            repository,
            cookies,
            Clock.fixed(Instant.parse("2026-10-11T15:04:59Z"), ZoneOffset.UTC),
            TransactionTemplate(manager),
        )
        grace.cleanup()
        verifyNoInteractions(repository)
        whenever(repository.expiredDays(LocalDate.of(2026, 10, 12))).thenReturn(listOf(LocalDate.of(2026, 10, 11)))
        service.cleanup()
        verify(repository).lockDay(LocalDate.of(2026, 10, 11), true)
        verify(repository).deleteDay(LocalDate.of(2026, 10, 11))
        verify(repository, never()).count(anyNonNull(), anyNonNull())
    }
}
