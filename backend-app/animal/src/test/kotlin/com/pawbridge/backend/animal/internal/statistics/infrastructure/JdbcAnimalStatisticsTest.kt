package com.pawbridge.backend.animal.internal.statistics.infrastructure

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.DailyCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.IntakeTrend
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.RegionalCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Status
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.StatusCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Today
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.stream.Stream
import javax.sql.DataSource

class JdbcAnimalStatisticsTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
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
    fun givenUtcInstantAroundSeoulMidnight__whenReadingToday__thenUseIntakeAndApmsUpdateDateWithoutConflatingStorageOrAdoptionEvents(
        instant: String,
        expected: LocalDate,
    ) {
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(23L, 5L)

        assertThat(service(instant).today()).isEqualTo(Today(expected, 23L, 5L))

        verify(jdbc).queryForObject(
            eq("SELECT count(*) FROM pawbridge_animal.animals WHERE happen_date=:today"),
            argThat(ArgumentMatcher<MapSqlParameterSource> { p -> p.getValue("today") == expected }),
            eq(Long::class.javaObjectType),
        )
        verify(jdbc).queryForObject(
            eq("SELECT count(*) FROM pawbridge_animal.animals WHERE status=:status AND CAST(apms_updated_at AS date)=:today"),
            argThat(
                ArgumentMatcher<MapSqlParameterSource> { p ->
                    p.getValue("today") == expected && p.getValue("status") == "ADOPTED"
                },
            ),
            eq(Long::class.javaObjectType),
        )
        verifyNoMoreInteractions(jdbc)
    }

    @ParameterizedTest
    @MethodSource("publicRanges")
    fun givenOptionalPublicBounds__whenReadingStatusAndRegional__thenDefaultOnlyMissingBoundsWithoutNewRangePolicy(
        requestedStart: LocalDate?,
        requestedEnd: LocalDate?,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
    ) {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Any>>())).thenReturn(listOf())

        assertThat(statistics.status(requestedStart, requestedEnd)).isEmpty()
        assertThat(statistics.regional(requestedStart, requestedEnd)).isEmpty()

        val parameters = ArgumentCaptor.forClass(MapSqlParameterSource::class.java)
        verify(jdbc, times(2)).query(anyString(), parameters.capture(), any<RowMapper<Any>>())
        for (actual in parameters.allValues) {
            assertThat(actual.getValue("startDate")).isEqualTo(expectedStart)
            assertThat(actual.getValue("endDate")).isEqualTo(expectedEnd)
        }
    }

    @Test
    fun givenCurrentStatusAggregateRows__whenReading__thenPreserveCountOrderLabelsAndOnlyReturnedStatuses() {
        val protect = mock(ResultSet::class.java)
        val adopted = mock(ResultSet::class.java)
        `when`(protect.getString("status")).thenReturn("PROTECT")
        `when`(protect.getObject("animal_count", Long::class.javaObjectType)).thenReturn(9876543210L)
        `when`(adopted.getString("status")).thenReturn("ADOPTED")
        `when`(adopted.getObject("animal_count", Long::class.javaObjectType)).thenReturn(2L)
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<StatusCount>>()))
            .thenAnswer { call ->
                val mapper = call.getArgument<RowMapper<StatusCount>>(2)
                listOf(mapper.mapRow(protect, 0), mapper.mapRow(adopted, 1))
            }

        assertThat(statistics.status(TODAY, TODAY))
            .containsExactly(StatusCount(Status.PROTECT, 9876543210L), StatusCount(Status.ADOPTED, 2L))

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("WHERE happen_date BETWEEN :startDate AND :endDate") &&
                        sql.contains("GROUP BY status ORDER BY count(*) DESC") &&
                        !sql.contains("created_at") && !sql.contains("notice_start_date")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<StatusCount>>(),
        )
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
    fun givenAllSeventeenRegionNames__whenAggregating__thenPreserveOriginalMappingAndStableTies() {
        val names = arrayOf(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시",
            "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도", "경상북도", "경상남도", "제주특별자치도",
        )
        val rows = ArrayList<ResultSet>()
        for (name in names) {
            rows.add(shelterRow(name + " 지역", 1))
        }
        stubShelters(rows)

        assertThat(statistics.regional(TODAY, TODAY).map { count -> count.region }).containsExactly(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
        )

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id") &&
                        sql.contains("WHERE a.happen_date BETWEEN :startDate AND :endDate") &&
                        sql.contains("GROUP BY s.id ORDER BY count(a.id) DESC")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Any>>(),
        )
    }

    @Test
    fun givenDuplicateRegionsMissingAddressAndUnusualTokens__whenAggregating__thenKeepOriginalSkipFirstTokenMergeAndCountOrder() {
        stubShelters(
            listOf(
                shelterRow(null, 999), shelterRow(" \t ", 998), shelterRow("서울특별시 종로구", 10), shelterRow("서울 강남구", 5),
                shelterRow("부산 중구", 4), shelterRow("Unknown Place", 4), shelterRow(" 서울 강남구", 3),
                shelterRow("경기도광주 특이토큰", 2),
            ),
        )

        assertThat(statistics.regional(TODAY, TODAY)).containsExactly(
            RegionalCount("서울", 15L), RegionalCount("부산", 4L),
            RegionalCount("Unknown", 4L), RegionalCount("", 3L), RegionalCount("광주", 2L),
        )
    }

    @Test
    fun givenDailyStorageCounts__whenReadingDailyAnimals__thenUseUtcStoragePlusNineHoursAndReturnOnlyExistingDates() {
        val first = dailyRow(TODAY.minusDays(2), 3)
        val last = dailyRow(TODAY, 9876543210L)
        stubDaily(listOf(first, last))

        assertThat(statistics.dailyAnimals(TODAY.minusDays(2), TODAY))
            .containsExactly(DailyCount(TODAY.minusDays(2), 3L), DailyCount(TODAY, 9876543210L))

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("CAST(a.created_at + INTERVAL '9 hours' AS date)") &&
                        sql.contains("BETWEEN :startDate AND :endDate") &&
                        !sql.contains("happen_date") && !sql.contains("apms_updated_at")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<DailyCount>>(),
        )
    }

    @Test
    fun givenReversedOrFutureDailyRange__whenReadingDailyAnimals__thenKeepUnvalidatedSourceQuery() {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<DailyCount>>()))
            .thenReturn(listOf())

        assertThat(statistics.dailyAnimals(TODAY.plusDays(3), TODAY)).isEmpty()

        verify(jdbc).query(
            anyString(),
            argThat(
                ArgumentMatcher<MapSqlParameterSource> { p ->
                    p.getValue("startDate") == TODAY.plusDays(3) && p.getValue("endDate") == TODAY
                },
            ),
            any<RowMapper<DailyCount>>(),
        )
    }

    @Test
    fun givenPreviousIntakeDayAndMissingSelectedDate__whenReadingTrend__thenSeparateComparisonAndDoNotFillGaps() {
        val start = TODAY.minusDays(2)
        val previous = start.minusDays(1)
        stubDaily(listOf(dailyRow(previous, 7), dailyRow(start, 2), dailyRow(TODAY, 4)))

        assertThat(statistics.intakeTrend(start, TODAY)).isEqualTo(
            IntakeTrend(start, TODAY, listOf(DailyCount(start, 2L), DailyCount(TODAY, 4L)), 7),
        )

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("SELECT a.happen_date AS stat_date") &&
                        sql.contains("GROUP BY a.happen_date ORDER BY a.happen_date") && !sql.contains("created_at")
                },
            ),
            argThat(
                ArgumentMatcher<MapSqlParameterSource> { p ->
                    p.getValue("startDate") == previous && p.getValue("endDate") == TODAY
                },
            ),
            any<RowMapper<DailyCount>>(),
        )
    }

    @Test
    fun givenNoRecordsAnd366DayTrend__whenReading__thenKeepEmptySeriesZeroComparisonAndInternalPreviousDay() {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<DailyCount>>()))
            .thenReturn(listOf())
        val start = TODAY.minusDays(365)

        assertThat(statistics.intakeTrend(start, TODAY)).isEqualTo(IntakeTrend(start, TODAY, listOf(), 0))

        verify(jdbc).query(
            anyString(),
            argThat(ArgumentMatcher<MapSqlParameterSource> { p -> p.getValue("startDate") == start.minusDays(1) }),
            any<RowMapper<DailyCount>>(),
        )
    }

    @ParameterizedTest
    @MethodSource("invalidTrends")
    fun givenInvalidTrendRange__whenReading__thenKeepSource400ContractBeforeQuery(start: LocalDate?, end: LocalDate?) {
        assertThatThrownBy { statistics.intakeTrend(start, end) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.")

        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenStorageFailure__whenReadingTrend__thenDoNotReturnSyntheticZeroSeries() {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<DailyCount>>()))
            .thenThrow(DataAccessResourceFailureException("synthetic source unavailable"))

        assertThatThrownBy { statistics.intakeTrend(TODAY, TODAY) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
    }

    @Test
    fun givenPublicConstructor__whenReading__thenRetainOriginalReadOnlyDefaultIsolationAndTimeout() {
        val source = mock(DataSource::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        `when`(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        `when`(source.connection).thenThrow(SQLException("synthetic disconnected source"))
        val actual = JdbcAnimalStatistics(source, manager, Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC))

        assertThatThrownBy { actual.today() }.isInstanceOf(DataAccessException::class.java)

        val definition = ArgumentCaptor.forClass(TransactionDefinition::class.java)
        verify(manager).getTransaction(definition.capture())
        assertThat(definition.value.isReadOnly).isTrue()
        assertThat(definition.value.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definition.value.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        verify(manager).rollback(any())
    }

    private fun stubShelters(rows: List<ResultSet>) {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Any>>())).thenAnswer { call ->
            val mapped = ArrayList<Any?>()
            for (i in 0 until rows.size) {
                mapped.add(call.getArgument<RowMapper<Any>>(2).mapRow(rows[i], i))
            }
            mapped
        }
    }

    private fun stubDaily(rows: List<ResultSet>) {
        stubShelters(rows)
    }

    private fun shelterRow(address: String?, count: Long): ResultSet {
        val row = mock(ResultSet::class.java)
        `when`(row.getString("address")).thenReturn(address)
        `when`(row.getObject("animal_count", Long::class.javaObjectType)).thenReturn(count)
        return row
    }

    private fun dailyRow(day: LocalDate, count: Long): ResultSet {
        val row = mock(ResultSet::class.java)
        `when`(row.getObject("stat_date", LocalDate::class.java)).thenReturn(day)
        `when`(row.getObject("animal_count", Long::class.javaObjectType)).thenReturn(count)
        return row
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
