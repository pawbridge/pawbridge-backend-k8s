package com.pawbridge.backend.animal.internal.statistics.infrastructure;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAnimalStatisticsTest {
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private final JdbcAnimalStatistics statistics = service("2026-10-01T15:00:00Z");

    private JdbcAnimalStatistics service(String instant) {
        return new JdbcAnimalStatistics(jdbc, TransactionOperations.withoutTransaction(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @MethodSource("midnight")
    void givenUtcInstantAroundSeoulMidnight__whenReadingToday__thenUseIntakeAndApmsUpdateDateWithoutConflatingStorageOrAdoptionEvents(
            String instant, LocalDate expected) {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(23L, 5L);
        assertThat(service(instant).today()).isEqualTo(new Today(expected, 23L, 5L));
        verify(jdbc).queryForObject(eq("SELECT count(*) FROM pawbridge_animal.animals WHERE happen_date=:today"),
                argThat((MapSqlParameterSource p) -> p.getValue("today").equals(expected)), eq(Long.class));
        verify(jdbc).queryForObject(eq("SELECT count(*) FROM pawbridge_animal.animals WHERE status=:status AND CAST(apms_updated_at AS date)=:today"),
                argThat((MapSqlParameterSource p) -> p.getValue("today").equals(expected) && p.getValue("status").equals("ADOPTED")), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }
    static Stream<Arguments> midnight() {
        return Stream.of(Arguments.of("2026-10-01T14:59:59Z", TODAY.minusDays(1)), Arguments.of("2026-10-01T15:00:00Z", TODAY));
    }

    @ParameterizedTest
    @MethodSource("publicRanges")
    void givenOptionalPublicBounds__whenReadingStatusAndRegional__thenDefaultOnlyMissingBoundsWithoutNewRangePolicy(
            LocalDate requestedStart, LocalDate requestedEnd, LocalDate expectedStart, LocalDate expectedEnd) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenReturn(List.of());
        assertThat(statistics.status(requestedStart, requestedEnd)).isEmpty();
        assertThat(statistics.regional(requestedStart, requestedEnd)).isEmpty();
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).query(anyString(), parameters.capture(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
        for (var actual : parameters.getAllValues()) {
            assertThat(actual.getValue("startDate")).isEqualTo(expectedStart);
            assertThat(actual.getValue("endDate")).isEqualTo(expectedEnd);
        }
    }
    static Stream<Arguments> publicRanges() {
        return Stream.of(Arguments.of(null, null, TODAY.minusDays(29), TODAY),
                Arguments.of(null, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1)),
                Arguments.of(TODAY.minusDays(10), null, TODAY.minusDays(10), TODAY),
                Arguments.of(TODAY, TODAY.minusDays(1), TODAY, TODAY.minusDays(1)),
                Arguments.of(TODAY.minusDays(700), TODAY.plusDays(3), TODAY.minusDays(700), TODAY.plusDays(3)));
    }

    @Test
    void givenCurrentStatusAggregateRows__whenReading__thenPreserveCountOrderLabelsAndOnlyReturnedStatuses() throws Exception {
        ResultSet protect = mock(ResultSet.class), adopted = mock(ResultSet.class);
        when(protect.getString("status")).thenReturn("PROTECT");
        when(protect.getObject("animal_count", Long.class)).thenReturn(9876543210L);
        when(adopted.getString("status")).thenReturn("ADOPTED");
        when(adopted.getObject("animal_count", Long.class)).thenReturn(2L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<StatusCount>>any()))
                .thenAnswer(call -> List.of(call.<RowMapper<StatusCount>>getArgument(2).mapRow(protect, 0), call.<RowMapper<StatusCount>>getArgument(2).mapRow(adopted, 1)));
        assertThat(statistics.status(TODAY, TODAY)).containsExactly(new StatusCount(Status.PROTECT, 9876543210L), new StatusCount(Status.ADOPTED, 2L));
        verify(jdbc).query(argThat((String sql) -> sql.contains("WHERE happen_date BETWEEN :startDate AND :endDate")
                && sql.contains("GROUP BY status ORDER BY count(*) DESC") && !sql.contains("created_at") && !sql.contains("notice_start_date")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<StatusCount>>any());
        assertThat(List.of(Status.values())).extracting(Status::name).containsExactly("NOTICE", "PROTECT", "ADOPTION_PENDING", "ADOPTED",
                "EUTHANIZED", "NATURAL_DEATH", "RETURNED", "DONATED", "RELEASED", "ESCAPED", "UNKNOWN");
        assertThat(List.of(Status.values())).extracting(Status::label).containsExactly("공고중", "보호중", "입양대기중", "종료(입양)",
                "종료(안락사)", "종료(자연사)", "종료(반환)", "종료(기증)", "종료(방사)", "탈출", "미상");
    }

    @Test
    void givenAllSeventeenRegionNames__whenAggregating__thenPreserveOriginalMappingAndStableTies() throws Exception {
        String[] names = {"서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시",
                "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도", "경상북도", "경상남도", "제주특별자치도"};
        List<ResultSet> rows = new ArrayList<>();
        for (String name : names) rows.add(shelterRow(name + " 지역", 1));
        stubShelters(rows);
        assertThat(statistics.regional(TODAY, TODAY)).extracting(RegionalCount::region).containsExactly(
                "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
        verify(jdbc).query(argThat((String sql) -> sql.contains("JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id")
                && sql.contains("WHERE a.happen_date BETWEEN :startDate AND :endDate") && sql.contains("GROUP BY s.id ORDER BY count(a.id) DESC")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
    }

    @Test
    void givenDuplicateRegionsMissingAddressAndUnusualTokens__whenAggregating__thenKeepOriginalSkipFirstTokenMergeAndCountOrder() throws Exception {
        stubShelters(List.of(shelterRow(null, 999), shelterRow(" \t ", 998), shelterRow("서울특별시 종로구", 10), shelterRow("서울 강남구", 5),
                shelterRow("부산 중구", 4), shelterRow("Unknown Place", 4), shelterRow(" 서울 강남구", 3), shelterRow("경기도광주 특이토큰", 2)));
        assertThat(statistics.regional(TODAY, TODAY)).containsExactly(new RegionalCount("서울", 15L), new RegionalCount("부산", 4L),
                new RegionalCount("Unknown", 4L), new RegionalCount("", 3L), new RegionalCount("광주", 2L));
    }

    @Test
    void givenDailyStorageCounts__whenReadingDailyAnimals__thenUseUtcStoragePlusNineHoursAndReturnOnlyExistingDates() throws Exception {
        ResultSet first = dailyRow(TODAY.minusDays(2), 3), last = dailyRow(TODAY, 9876543210L);
        stubDaily(List.of(first, last));
        assertThat(statistics.dailyAnimals(TODAY.minusDays(2), TODAY)).containsExactly(new DailyCount(TODAY.minusDays(2), 3L), new DailyCount(TODAY, 9876543210L));
        verify(jdbc).query(argThat((String sql) -> sql.contains("CAST(a.created_at + INTERVAL '9 hours' AS date)")
                && sql.contains("BETWEEN :startDate AND :endDate") && !sql.contains("happen_date") && !sql.contains("apms_updated_at")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any());
    }

    @Test
    void givenReversedOrFutureDailyRange__whenReadingDailyAnimals__thenKeepUnvalidatedSourceQuery() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any())).thenReturn(List.of());
        assertThat(statistics.dailyAnimals(TODAY.plusDays(3), TODAY)).isEmpty();
        verify(jdbc).query(anyString(), argThat((MapSqlParameterSource p) -> p.getValue("startDate").equals(TODAY.plusDays(3))
                && p.getValue("endDate").equals(TODAY)), org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any());
    }

    @Test
    void givenPreviousIntakeDayAndMissingSelectedDate__whenReadingTrend__thenSeparateComparisonAndDoNotFillGaps() throws Exception {
        LocalDate start = TODAY.minusDays(2), previous = start.minusDays(1);
        stubDaily(List.of(dailyRow(previous, 7), dailyRow(start, 2), dailyRow(TODAY, 4)));
        assertThat(statistics.intakeTrend(start, TODAY)).isEqualTo(new IntakeTrend(start, TODAY,
                List.of(new DailyCount(start, 2L), new DailyCount(TODAY, 4L)), 7));
        verify(jdbc).query(argThat((String sql) -> sql.contains("SELECT a.happen_date AS stat_date") && sql.contains("GROUP BY a.happen_date ORDER BY a.happen_date")
                && !sql.contains("created_at")), argThat((MapSqlParameterSource p) -> p.getValue("startDate").equals(previous)
                && p.getValue("endDate").equals(TODAY)), org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any());
    }

    @Test
    void givenNoRecordsAnd366DayTrend__whenReading__thenKeepEmptySeriesZeroComparisonAndInternalPreviousDay() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any())).thenReturn(List.of());
        LocalDate start = TODAY.minusDays(365);
        assertThat(statistics.intakeTrend(start, TODAY)).isEqualTo(new IntakeTrend(start, TODAY, List.of(), 0));
        verify(jdbc).query(anyString(), argThat((MapSqlParameterSource p) -> p.getValue("startDate").equals(start.minusDays(1))),
                org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any());
    }

    @ParameterizedTest
    @MethodSource("invalidTrends")
    void givenInvalidTrendRange__whenReading__thenKeepSource400ContractBeforeQuery(LocalDate start, LocalDate end) {
        assertThatThrownBy(() -> statistics.intakeTrend(start, end)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.");
        verifyNoInteractions(jdbc);
    }
    static Stream<Arguments> invalidTrends() {
        return Stream.of(Arguments.of(null, TODAY), Arguments.of(TODAY, null), Arguments.of(TODAY, TODAY.minusDays(1)),
                Arguments.of(TODAY.minusDays(366), TODAY), Arguments.of(TODAY, TODAY.plusDays(1)));
    }

    @Test
    void givenStorageFailure__whenReadingTrend__thenDoNotReturnSyntheticZeroSeries() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DailyCount>>any()))
                .thenThrow(new DataAccessResourceFailureException("synthetic source unavailable"));
        assertThatThrownBy(() -> statistics.intakeTrend(TODAY, TODAY)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void givenPublicConstructor__whenReading__thenRetainOriginalReadOnlyDefaultIsolationAndTimeout() throws Exception {
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(source.getConnection()).thenThrow(new SQLException("synthetic disconnected source"));
        var actual = new JdbcAnimalStatistics(source, manager, Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(actual::today).isInstanceOf(DataAccessException.class);
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(definition.getValue().getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        verify(manager).rollback(any());
    }

    private void stubShelters(List<ResultSet> rows) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            List<Object> mapped = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) mapped.add(call.<RowMapper<Object>>getArgument(2).mapRow(rows.get(i), i));
            return mapped;
        });
    }
    private void stubDaily(List<ResultSet> rows) { stubShelters(rows); }
    private static ResultSet shelterRow(String address, long count) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("address")).thenReturn(address);
        when(row.getObject("animal_count", Long.class)).thenReturn(count);
        return row;
    }
    private static ResultSet dailyRow(LocalDate day, long count) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("stat_date", LocalDate.class)).thenReturn(day);
        when(row.getObject("animal_count", Long.class)).thenReturn(count);
        return row;
    }
}
