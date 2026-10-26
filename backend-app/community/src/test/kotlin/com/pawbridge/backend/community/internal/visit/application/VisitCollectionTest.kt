package com.pawbridge.backend.community.internal.visit.application

import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollection
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class VisitCollectionTest {
    private val repository = mockk<VisitCollectionStore>()
    private val cookies = VisitIdentity(VisitIdentityTest.KEY)
    private val manager = mockk<PlatformTransactionManager>()
    private val now = Instant.parse("2026-10-12T01:00:00Z")
    private lateinit var service: VisitCollection

    @BeforeEach
    fun setup() {
        service = JdbcVisitCollection(repository, cookies, Clock.fixed(now, ZoneOffset.UTC), TransactionTemplate(manager))
    }

    @Test
    fun `쿠키가 없을 때__집계하면__저장소에 닿지 않고 충돌로 거부한다`() {
        // given
        val cookie: String? = null

        // when
        val thrown = assertThatThrownBy { service.count(cookie) }

        // then
        thrown.isInstanceOf(VisitCollectionRejectedException::class.java)
        confirmVerified(repository)
    }

    @Test
    fun `올바른 쿠키가 있을 때__집계하면__해시만 저장하고 커밋한다`() {
        // given
        val value = cookies.initialize(null, now)!!.value

        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { repository.lockDay(any(), any()) }
        justRun { repository.count(any(), any()) }
        every { repository.today(any()) } returns 7L
        justRun { manager.commit(any()) }

        // when
        val result = service.count(value)

        // then
        assertThat(result.count).isEqualTo(7)
        verifyOrder {
            repository.lockDay(LocalDate.of(2026, 10, 12), false)
            repository.count(LocalDate.of(2026, 10, 12), match<String> { it.matches(Regex("[0-9a-f]{64}")) })
            repository.today(LocalDate.of(2026, 10, 12))
            manager.commit(any())
        }
        verify(exactly = 1) { manager.commit(any()) }
    }

    @Test
    fun `요청이 한국 자정을 넘겨 기다릴 때__날짜 잠금을 얻으면__거부하고 롤백한다`() {
        // given
        val changing = mockk<Clock>()
        val before = Instant.parse("2026-10-12T14:59:59Z")
        val midnight = JdbcVisitCollection(repository, cookies, changing, TransactionTemplate(manager))

        every { changing.instant() } returnsMany listOf(before, before.plusSeconds(2))
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { repository.lockDay(any(), any()) }
        justRun { manager.rollback(any()) }

        // when
        val thrown = assertThatThrownBy { midnight.count(cookies.initialize(null, before)!!.value) }

        // then
        thrown.isInstanceOf(VisitCollectionRejectedException::class.java)
        verify(exactly = 1) { repository.lockDay(LocalDate.of(2026, 10, 12), false) }
        verify(exactly = 0) { repository.count(any(), any()) }
        verify(exactly = 1) { manager.rollback(any()) }
    }

    @Test
    fun `기간 중간에 시작했을 때__기간을 조회하면__수집 전 날짜는 빼고 수집된 날은 0으로 채운다`() {
        // given
        every { repository.startedAt() } returns Instant.parse("2026-10-10T03:00:00Z")
        every { repository.period(any(), any()) } returns listOf(VisitCollection.Day(LocalDate.of(2026, 10, 12), 2))

        // when
        val result = service.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 12))

        // then
        assertThat(result.daily).containsExactly(
            VisitCollection.Day(LocalDate.of(2026, 10, 10), 0),
            VisitCollection.Day(LocalDate.of(2026, 10, 11), 0),
            VisitCollection.Day(LocalDate.of(2026, 10, 12), 2),
        )
    }

    @Test
    fun `미래이거나 뒤집혔거나 지나치게 긴 범위일 때__기간을 조회하면__질의 전에 거부한다`() {
        // given
        val future = Pair(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13))
        val reversed = Pair(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11))
        val excessive = Pair(LocalDate.of(2025, 10, 11), LocalDate.of(2026, 10, 12))

        // when
        val futureThrown = assertThatThrownBy { service.period(future.first, future.second) }
        val reversedThrown = assertThatThrownBy { service.period(reversed.first, reversed.second) }
        val excessiveThrown = assertThatThrownBy { service.period(excessive.first, excessive.second) }

        // then
        futureThrown.isInstanceOf(VisitCollectionRejectedException::class.java)
        reversedThrown.isInstanceOf(VisitCollectionRejectedException::class.java)
        excessiveThrown.isInstanceOf(VisitCollectionRejectedException::class.java)
        confirmVerified(repository)
    }

    @Test
    fun `자정 유예 시간 안일 때__정리하면__5분이 지날 때까지 기다리고 집계는 보존한다`() {
        // given
        val grace = JdbcVisitCollection(
            repository,
            cookies,
            Clock.fixed(Instant.parse("2026-10-11T15:04:59Z"), ZoneOffset.UTC),
            TransactionTemplate(manager),
        )

        every { repository.expiredDays(any()) } returns listOf(LocalDate.of(2026, 10, 11))
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { repository.lockDay(any(), any()) }
        justRun { repository.deleteDay(any()) }
        justRun { manager.commit(any()) }

        // when
        grace.cleanup()

        // then
        confirmVerified(repository)

        // when
        service.cleanup()

        // then
        verify(exactly = 1) { repository.expiredDays(LocalDate.of(2026, 10, 12)) }
        verify(exactly = 1) { repository.lockDay(LocalDate.of(2026, 10, 11), true) }
        verify(exactly = 1) { repository.deleteDay(LocalDate.of(2026, 10, 11)) }
        verify(exactly = 0) { repository.count(any(), any()) }
    }
}
