package com.pawbridge.backend.animal.internal.statistics.infrastructure

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.DailyCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.IntakeTrend
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.RegionalCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Status
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.StatusCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Today
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.stream.Stream
import javax.sql.DataSource

class JdbcAnimalStatisticsTest {
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val statistics = service("2026-10-01T15:00:00Z")

    private fun service(instant: String): JdbcAnimalStatistics {
        return JdbcAnimalStatistics(
            jdbc,
            TransactionOperations.withoutTransaction(),
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
        )
    }

    @ParameterizedTest
    @MethodSource("midnight")
    fun `UTC 시각이 서울 자정 전후일 때__오늘을 읽으면__보관이나 입양 이벤트와 섞지 않고 접수일과 APMS 갱신일을 쓴다`(
        instant: String,
        expected: LocalDate,
    ) {
        // given
        every {
            jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        } returnsMany listOf(23L, 5L)

        // when
        val today = service(instant).today()

        // then
        assertThat(today).isEqualTo(Today(expected, 23L, 5L))
        verify(exactly = 1) {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE happen_date=:today",
                match<MapSqlParameterSource> { p -> p.getValue("today") == expected },
                Long::class.javaObjectType,
            )
        }
        verify(exactly = 1) {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE status=:status AND CAST(apms_updated_at AS date)=:today",
                match<MapSqlParameterSource> { p ->
                    p.getValue("today") == expected && p.getValue("status") == "ADOPTED"
                },
                Long::class.javaObjectType,
            )
        }
        confirmVerified(jdbc)
    }

    @ParameterizedTest
    @MethodSource("publicRanges")
    fun `공개 경계가 선택 사항일 때__상태와 지역을 읽으면__없는 경계만 기본값으로 채우고 새 범위 정책은 두지 않는다`(
        requestedStart: LocalDate?,
        requestedEnd: LocalDate?,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
    ) {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Any>>()) } returns listOf()

        // when
        val status = statistics.status(requestedStart, requestedEnd)
        val regional = statistics.regional(requestedStart, requestedEnd)

        // then
        val parameters = mutableListOf<MapSqlParameterSource>()
        assertThat(status).isEmpty()
        assertThat(regional).isEmpty()
        verify(exactly = 2) { jdbc.query(any<String>(), capture(parameters), any<RowMapper<Any>>()) }
        for (actual in parameters) {
            assertThat(actual.getValue("startDate")).isEqualTo(expectedStart)
            assertThat(actual.getValue("endDate")).isEqualTo(expectedEnd)
        }
    }

    @Test
    fun `현재 상태 집계 행이 있을 때__읽으면__건수 순서와 라벨과 반환된 상태만 보존한다`() {
        // given
        val protect = row("status" to "PROTECT", "animal_count" to 9876543210L)
        val adopted = row("status" to "ADOPTED", "animal_count" to 2L)

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<StatusCount>>()) } answers {
            val mapper = arg<RowMapper<StatusCount>>(2)
            listOf(mapper.mapRow(protect, 0)!!, mapper.mapRow(adopted, 1)!!)
        }

        // when
        val counts = statistics.status(TODAY, TODAY)

        // then
        assertThat(counts)
            .containsExactly(StatusCount(Status.PROTECT, 9876543210L), StatusCount(Status.ADOPTED, 2L))
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("WHERE happen_date BETWEEN :startDate AND :endDate") &&
                        sql.contains("GROUP BY status ORDER BY count(*) DESC") &&
                        !sql.contains("created_at") && !sql.contains("notice_start_date")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<StatusCount>>(),
            )
        }
        assertThat(listOf(*Status.values()).map { status -> status.name }).containsExactly(
            "NOTICE", "PROTECT", "ADOPTION_PENDING", "ADOPTED",
            "EUTHANIZED", "NATURAL_DEATH", "RETURNED", "DONATED", "RELEASED", "ESCAPED", "UNKNOWN",
        )
        assertThat(listOf(*Status.values()).map { status -> status.label() }).containsExactly(
            "공고중", "보호중", "입양대기중", "종료(입양)",
            "종료(안락사)", "종료(자연사)", "종료(반환)", "종료(기증)", "종료(방사)", "탈출", "미상",
        )
    }

    @Test
    fun `17개 시도 이름이 모두 있을 때__집계하면__원래 매핑과 안정적인 동순위 순서를 보존한다`() {
        // given
        val names = arrayOf(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시",
            "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도", "경상북도", "경상남도", "제주특별자치도",
        )
        val rows = ArrayList<ResultSet>()
        for (name in names) {
            rows.add(shelterRow(name + " 지역", 1))
        }

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            val mapper = arg<RowMapper<Any>>(2)
            rows.mapIndexed { index, row -> mapper.mapRow(row, index)!! }
        }

        // when
        val regions = statistics.regional(TODAY, TODAY).map { count -> count.region }

        // then
        assertThat(regions).containsExactly(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
        )
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id") &&
                        sql.contains("WHERE a.happen_date BETWEEN :startDate AND :endDate") &&
                        sql.contains("GROUP BY s.id ORDER BY count(a.id) DESC")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Any>>(),
            )
        }
    }

    @Test
    fun `지역이 중복되고 주소가 없거나 특이한 토큰이 있을 때__집계하면__원래대로 첫 토큰 건너뛰기와 병합과 건수 순서를 유지한다`() {
        // given
        val rows = listOf(
            shelterRow(null, 999), shelterRow(" \t ", 998), shelterRow("서울특별시 종로구", 10), shelterRow("서울 강남구", 5),
            shelterRow("부산 중구", 4), shelterRow("Unknown Place", 4), shelterRow(" 서울 강남구", 3),
            shelterRow("경기도광주 특이토큰", 2),
        )

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            val mapper = arg<RowMapper<Any>>(2)
            rows.mapIndexed { index, row -> mapper.mapRow(row, index)!! }
        }

        // when
        val regional = statistics.regional(TODAY, TODAY)

        // then
        assertThat(regional).containsExactly(
            RegionalCount("서울", 15L), RegionalCount("부산", 4L),
            RegionalCount("Unknown", 4L), RegionalCount("", 3L), RegionalCount("광주", 2L),
        )
    }

    @Test
    fun `일별 보관 건수가 있을 때__일별 동물을 읽으면__UTC 보관 시각에 9시간을 더해 쓰고 존재하는 날짜만 돌려준다`() {
        // given
        val first = dailyRow(TODAY.minusDays(2), 3)
        val last = dailyRow(TODAY, 9876543210L)

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<DailyCount>>()) } answers {
            val mapper = arg<RowMapper<DailyCount>>(2)
            listOf(mapper.mapRow(first, 0)!!, mapper.mapRow(last, 1)!!)
        }

        // when
        val counts = statistics.dailyAnimals(TODAY.minusDays(2), TODAY)

        // then
        assertThat(counts)
            .containsExactly(DailyCount(TODAY.minusDays(2), 3L), DailyCount(TODAY, 9876543210L))
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("CAST(a.created_at + INTERVAL '9 hours' AS date)") &&
                        sql.contains("BETWEEN :startDate AND :endDate") &&
                        !sql.contains("happen_date") && !sql.contains("apms_updated_at")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<DailyCount>>(),
            )
        }
    }

    @Test
    fun `거꾸로이거나 미래인 일별 범위일 때__일별 동물을 읽으면__검증하지 않는 원래 쿼리를 유지한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<DailyCount>>()) } returns listOf()

        // when
        val counts = statistics.dailyAnimals(TODAY.plusDays(3), TODAY)

        // then
        assertThat(counts).isEmpty()
        verify(exactly = 1) {
            jdbc.query(
                any<String>(),
                match<MapSqlParameterSource> { p ->
                    p.getValue("startDate") == TODAY.plusDays(3) && p.getValue("endDate") == TODAY
                },
                any<RowMapper<DailyCount>>(),
            )
        }
    }

    @Test
    fun `이전 접수일과 선택 기간에 없는 날짜가 있을 때__추세를 읽으면__비교를 분리하고 빈 날을 채우지 않는다`() {
        // given
        val start = TODAY.minusDays(2)
        val previous = start.minusDays(1)
        val rows = listOf(dailyRow(previous, 7), dailyRow(start, 2), dailyRow(TODAY, 4))

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<DailyCount>>()) } answers {
            val mapper = arg<RowMapper<DailyCount>>(2)
            rows.mapIndexed { index, row -> mapper.mapRow(row, index)!! }
        }

        // when
        val trend = statistics.intakeTrend(start, TODAY)

        // then
        assertThat(trend).isEqualTo(
            IntakeTrend(start, TODAY, listOf(DailyCount(start, 2L), DailyCount(TODAY, 4L)), 7),
        )
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("SELECT a.happen_date AS stat_date") &&
                        sql.contains("GROUP BY a.happen_date ORDER BY a.happen_date") && !sql.contains("created_at")
                },
                match<MapSqlParameterSource> { p ->
                    p.getValue("startDate") == previous && p.getValue("endDate") == TODAY
                },
                any<RowMapper<DailyCount>>(),
            )
        }
    }

    @Test
    fun `기록이 없고 추세가 366일일 때__읽으면__빈 시리즈와 0 비교와 내부 이전 날짜를 유지한다`() {
        // given
        val start = TODAY.minusDays(365)

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<DailyCount>>()) } returns listOf()

        // when
        val trend = statistics.intakeTrend(start, TODAY)

        // then
        assertThat(trend).isEqualTo(IntakeTrend(start, TODAY, listOf(), 0))
        verify(exactly = 1) {
            jdbc.query(
                any<String>(),
                match<MapSqlParameterSource> { p -> p.getValue("startDate") == start.minusDays(1) },
                any<RowMapper<DailyCount>>(),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("invalidTrends")
    fun `추세 기간이 잘못됐을 때__읽으면__쿼리 전에 원래의 400 계약을 유지한다`(start: LocalDate?, end: LocalDate?) {
        // given
        // when
        val thrown = assertThatThrownBy { statistics.intakeTrend(start, end) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.")
        confirmVerified(jdbc)
    }

    @Test
    fun `저장소가 실패할 때__추세를 읽으면__지어낸 0 시리즈를 돌려주지 않는다`() {
        // given
        every {
            jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<DailyCount>>())
        } throws DataAccessResourceFailureException("synthetic source unavailable")

        // when
        val thrown = assertThatThrownBy { statistics.intakeTrend(TODAY, TODAY) }

        // then
        thrown.isInstanceOf(DataAccessResourceFailureException::class.java)
    }

    @Test
    fun `공개 생성자일 때__읽으면__원래의 읽기 전용 기본 격리와 기본 제한 시간을 유지한다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()

        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.rollback(any()) }
        every { source.connection } throws SQLException("synthetic disconnected source")
        val actual = JdbcAnimalStatistics(source, manager, Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC))

        // when
        val thrown = assertThatThrownBy { actual.today() }

        // then
        val definitions = mutableListOf<TransactionDefinition>()
        thrown.isInstanceOf(DataAccessException::class.java)
        verify(exactly = 1) { manager.getTransaction(capture(definitions)) }
        assertThat(definitions.single().isReadOnly).isTrue()
        assertThat(definitions.single().isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definitions.single().timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        verify(exactly = 1) { manager.rollback(any()) }
    }

    private fun shelterRow(address: String?, count: Long): ResultSet {
        return row("address" to address, "animal_count" to count)
    }

    private fun dailyRow(day: LocalDate, count: Long): ResultSet {
        return row("stat_date" to day, "animal_count" to count)
    }

    /**
     * A single-row in-memory ResultSet (a data fake, not a mock): `get*` with a column name reads that cell,
     * and every other method returns the type's zero value.
     */
    private fun row(vararg cells: Pair<String, Any?>): ResultSet {
        val values = cells.toMap()
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { proxy, method, args ->
            val name = method.name
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (name) {
                    "equals" -> proxy === args[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "InMemoryResultSet"
                }
            }
            if (name.startsWith("get") && args != null && args[0] is String) {
                return@newProxyInstance values[args[0] as String]
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as ResultSet
    }

    companion object {
        private val TODAY: LocalDate = LocalDate.of(2026, 10, 2)

        @JvmStatic
        fun midnight(): Stream<Arguments> = Stream.of(
            Arguments.of("2026-10-01T14:59:59Z", TODAY.minusDays(1)),
            Arguments.of("2026-10-01T15:00:00Z", TODAY),
        )

        @JvmStatic
        fun publicRanges(): Stream<Arguments> = Stream.of(
            Arguments.of(null, null, TODAY.minusDays(29), TODAY),
            Arguments.of(null, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1)),
            Arguments.of(TODAY.minusDays(10), null, TODAY.minusDays(10), TODAY),
            Arguments.of(TODAY, TODAY.minusDays(1), TODAY, TODAY.minusDays(1)),
            Arguments.of(TODAY.minusDays(700), TODAY.plusDays(3), TODAY.minusDays(700), TODAY.plusDays(3)),
        )

        @JvmStatic
        fun invalidTrends(): Stream<Arguments> = Stream.of(
            Arguments.of(null, TODAY),
            Arguments.of(TODAY, null),
            Arguments.of(TODAY, TODAY.minusDays(1)),
            Arguments.of(TODAY.minusDays(366), TODAY),
            Arguments.of(TODAY, TODAY.plusDays(1)),
        )
    }
}
