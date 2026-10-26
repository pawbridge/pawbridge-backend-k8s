package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollection
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollectionStore
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCountReader
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** The visit repositories called directly: the daily total read, the unique visitor store and the collection service. */
class VisitRepositoryContractTest : ApiContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransactionManager")
    private lateinit var manager: JdbcTransactionManager

    @Autowired
    @Qualifier("readJdbc")
    private lateinit var read: JdbcTemplate

    @Autowired
    @Qualifier("readOnlyTransaction")
    private lateinit var readTx: TransactionTemplate

    // 14:00 on 2026-03-10 in Seoul.
    private val noon: Instant = Instant.parse("2026-03-10T05:00:00Z")
    private val today: LocalDate = LocalDate.parse("2026-03-10")
    private val identity = VisitIdentity("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    private lateinit var store: JdbcVisitCollectionStore
    private lateinit var collection: VisitCollection

    @BeforeEach
    fun build() {
        store = JdbcVisitCollectionStore(write)
        collection = collectionAt(noon)
    }

    @Test
    fun `일별 집계 행이 있거나 없을 때__방문 합계를 읽으면__저장된 long 값이거나 0이다`() {
        // given
        execute("INSERT INTO pawbridge_community.daily_visit_count(visit_date, unique_visitors) VALUES ('2026-03-10', 5000000000), ('2026-03-11', 7)")
        val reader = JdbcVisitCountReader(read, readTx)

        // when
        // then
        assertThat(reader.count(today)).isEqualTo(5_000_000_000L)
        assertThat(reader.count(today.plusDays(1))).isEqualTo(7)
        assertThat(reader.count(today.minusDays(1))).isZero()
    }

    @Test
    fun `같은 방문자의 반복과 다른 방문자일 때__방문을 세면__방문자마다 하루 한 번만 합계가 늘어난다`() {
        // given
        val first = identity.initialize(null, noon)!!.value
        val second = identity.initialize(null, noon)!!.value

        // when
        val one = collection.count(first)
        val repeated = collection.count(first)
        val two = collection.count(second)
        val tomorrow = collectionAt(noon.plusSeconds(86_400)).let { it.count(identity.initialize(null, noon.plusSeconds(86_400))!!.value) }

        // then
        assertThat(one.count).isEqualTo(1)
        assertThat(one.date).isEqualTo(today)
        assertThat(repeated.count).isEqualTo(1)
        assertThat(two.count).isEqualTo(2)
        assertThat(tomorrow.count).`as`("Each day counts on its own").isEqualTo(1)
        assertThat(count("SELECT count(*) FROM pawbridge_community.daily_visitor WHERE visit_date = ?::date", today.toString())).isEqualTo(2)
        assertThat(scalar("SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date = ?::date", today.toString())).isEqualTo("2")
    }

    @Test
    fun `쿠키가 없거나 어제 것이거나 위조되었을 때__방문을 세면__쿠키 거부 예외이고 아무것도 세지 않는다`() {
        // given
        val valid = identity.initialize(null, noon)!!.value
        val yesterday = identity.initialize(null, noon.minusSeconds(86_400))!!.value

        // when
        // then
        assertNoSideEffects {
            for (cookie in listOf(null, "", valid.dropLast(2) + "xx", yesterday)) {
                assertThatThrownBy { collection.count(cookie) }
                    .isInstanceOfSatisfying(VisitCollectionRejectedException::class.java) {
                        assertThat(it.reason()).isEqualTo(VisitCollectionRejectedException.Reason.COOKIE)
                    }
            }
        }
    }

    @Test
    fun `수집 시작 이후 일부 날짜만 기록되었을 때__기간을 조회하면__시작일부터 빈 날짜를 0으로 채우고 범위를 넘으면 거부한다`() {
        // given
        collectionAt(Instant.parse("2026-02-28T15:00:00Z")).start()
        collection.start()
        execute("INSERT INTO pawbridge_community.daily_visit_count(visit_date, unique_visitors) VALUES ('2026-02-28', 9), ('2026-03-01', 5), ('2026-03-03', 2)")

        // when
        val period = collection.period(LocalDate.parse("2026-02-27"), LocalDate.parse("2026-03-03"))

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_community.visit_collection")).`as`("start() keeps the first start").isOne()
        assertThat(period.collectionStartedAt).isEqualTo(Instant.parse("2026-02-28T15:00:00Z"))
        assertThat(period.today).isEqualTo(today)
        assertThat(period.daily.map { it.date.toString() to it.count })
            .isEqualTo(listOf("2026-03-01" to 5L, "2026-03-02" to 0L, "2026-03-03" to 2L))
        for ((start, end) in listOf(today to today.minusDays(1), today to today.plusDays(1), today.minusDays(366) to today)) {
            assertThatThrownBy { collection.period(start, end) }
                .isInstanceOfSatisfying(VisitCollectionRejectedException::class.java) {
                    assertThat(it.reason()).isEqualTo(VisitCollectionRejectedException.Reason.RANGE)
                }
        }
    }

    @Test
    fun `지난 날짜의 방문자 기록이 있을 때__정리하면__지난 날짜만 지우고 오늘과 일 합계는 남기며 자정 직후에는 지우지 않는다`() {
        // given
        val hash = "a".repeat(64)
        execute(
            "INSERT INTO pawbridge_community.daily_visitor(visit_date, visitor_hash) VALUES (?::date, ?), (?::date, ?), (?::date, ?)",
            today.minusDays(2).toString(), hash, today.minusDays(1).toString(), hash, today.toString(), hash,
        )
        execute("INSERT INTO pawbridge_community.daily_visit_count(visit_date, unique_visitors) VALUES (?::date, 1)", today.minusDays(1).toString())

        // when
        collectionAt(Instant.parse("2026-03-09T15:02:00Z")).cleanup()
        val afterEarly = count("SELECT count(*) FROM pawbridge_community.daily_visitor")
        collection.cleanup()

        // then
        assertThat(afterEarly).`as`("Before 00:05 in Seoul nothing is removed").isEqualTo(3)
        assertThat(scalar("SELECT string_agg(visit_date::text, ',' ORDER BY visit_date) FROM pawbridge_community.daily_visitor")).isEqualTo(today.toString())
        assertThat(count("SELECT count(*) FROM pawbridge_community.daily_visit_count WHERE visit_date = ?::date", today.minusDays(1).toString())).isOne()
    }

    @Test
    fun `정리가 그날의 배타 잠금을 쥐고 있을 때__방문을 세면__잠금이 풀릴 때까지 기다린다`() {
        // given
        val cookie = identity.initialize(null, noon)!!.value
        val executor = Executors.newSingleThreadExecutor()

        // when
        val result = ContractPostgres.adminConnection { other ->
            other.autoCommit = false
            other.prepareStatement("SELECT pg_advisory_xact_lock(736241, ?)").use {
                it.setInt(1, Math.toIntExact(today.toEpochDay()))
                it.execute()
            }
            val pending = executor.submit<Long> { collection.count(cookie).count }
            Thread.sleep(800)
            val waiting = !pending.isDone
            other.commit()
            waiting to pending.get(10, TimeUnit.SECONDS)
        }
        executor.shutdown()

        // then
        assertThat(result.first).isTrue()
        assertThat(result.second).isEqualTo(1)
    }

    private fun collectionAt(instant: Instant): VisitCollection =
        JdbcVisitCollection(store, identity, Clock.fixed(instant, ZoneOffset.UTC), TransactionTemplate(manager))
}
