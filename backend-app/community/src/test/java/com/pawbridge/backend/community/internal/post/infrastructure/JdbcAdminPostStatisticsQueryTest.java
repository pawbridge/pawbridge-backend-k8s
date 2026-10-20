package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

class JdbcAdminPostStatisticsQueryTest {
    private static final LocalDate END = LocalDate.of(2024, 3, 1);

    @Test
    void given__sparse_daily_rows_and_unordered_board_rows__when__reading_period__then__separate_previous_day_without_filling_or_sorting_inside_one_rr_transaction() {
        var fixture = new Fixture();
        fixture.jdbc.results.add(List.of(row(END.minusDays(3), 3), row(END.minusDays(2), Long.MAX_VALUE), row(END, 2)));
        fixture.jdbc.results.add(List.of(Map.of("board_type", "REPORT", "post_count", 2L), Map.of("board_type", "ADOPTION", "post_count", Long.MAX_VALUE)));

        var result = fixture.query.period(END.minusDays(2), END);

        assertEquals(new PostPeriodStatsResponse(END.minusDays(2), END,
                List.of(new DailyPostStats(END.minusDays(2), Long.MAX_VALUE), new DailyPostStats(END, 2)), 3,
                List.of(new BoardTypeStats("REPORT", 2), new BoardTypeStats("ADOPTION", Long.MAX_VALUE))), result);
        assertEquals(List.of(new Range(END.minusDays(3).atStartOfDay(), END.plusDays(1).atStartOfDay()),
                new Range(END.minusDays(2).atStartOfDay(), END.plusDays(1).atStartOfDay())), fixture.jdbc.ranges);
        assertEquals(1, fixture.samples.get()); assertEquals(1, fixture.transactions.commits);
        assertEquals(List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ), fixture.transactions.isolations);
        assertTrue(fixture.jdbc.sql.getFirst().contains("GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)"));
        assertTrue(fixture.jdbc.sql.getFirst().contains("ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)"));
        assertFalse(fixture.jdbc.sql.getLast().contains("ORDER BY"));
        assertTrue(fixture.jdbc.sql.stream().allMatch(sql -> sql.contains("pawbridge_community.posts") && sql.contains("deleted_at IS NULL")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul"})
    void given__a_seoul_midnight_and_jvm_zone__when__reading_today_and_period__then__use_the_original_distinct_day_definitions_and_utc_calendar_binding(String zone) {
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var fixture = new Fixture(); fixture.jdbc.results.add(List.of(Map.of("post_count", Long.MAX_VALUE)));
            assertEquals(Long.MAX_VALUE, fixture.query.today());
            LocalDate today = zone.equals("UTC") ? END.minusDays(1) : END;
            assertEquals(List.of(new Range(today.atStartOfDay(), today.plusDays(1).atStartOfDay())), fixture.jdbc.ranges);
            assertEquals(List.of(TransactionDefinition.ISOLATION_DEFAULT), fixture.transactions.isolations);
            fixture.jdbc.results.add(List.of()); fixture.jdbc.results.add(List.of());
            assertEquals(new PostPeriodStatsResponse(END, END, List.of(), 0, List.of()), fixture.query.period(END, END));
            assertEquals(2, fixture.samples.get()); assertEquals(2, fixture.transactions.commits);
        } finally { TimeZone.setDefault(old); }
    }

    @Test
    void given__an_inclusive_366_day_range_without_posts__when__reading__then__allow_it_and_return_empty_lists() {
        var fixture = new Fixture(); fixture.jdbc.results.add(List.of()); fixture.jdbc.results.add(List.of());
        var result = fixture.query.period(END.minusDays(365), END);
        assertEquals(List.of(), result.daily()); assertEquals(0, result.previousDayCount()); assertEquals(List.of(), result.byBoardType());
        assertEquals(2, fixture.jdbc.ranges.size()); assertEquals(1, fixture.transactions.commits);
    }

    @ParameterizedTest
    @MethodSource("invalidRanges")
    void given__a_missing_reversed_future_or_367_day_range__when__reading__then__reject_before_sql(LocalDate start, LocalDate end) {
        var fixture = new Fixture();
        var failure = assertThrows(ResponseStatusException.class, () -> fixture.query.period(start, end));
        assertEquals(400, failure.getStatusCode().value());
        assertEquals("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.", failure.getReason());
        assertTrue(fixture.jdbc.ranges.isEmpty()); assertEquals(1, fixture.transactions.rollbacks); assertFalse(fixture.transactions.active);
    }
    static Stream<Arguments> invalidRanges() {
        return Stream.of(Arguments.of(null, END), Arguments.of(END, null), Arguments.of(END, END.minusDays(1)),
                Arguments.of(END, END.plusDays(1)), Arguments.of(END.minusDays(366), END));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void given__begin_second_query_or_commit_failure__when__reading_period__then__propagate_without_partial_results_retry_or_clock_before_begin(Failure phase) {
        var fixture = new Fixture(); RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("private-begin");
            case SECOND_QUERY -> new DataAccessResourceFailureException("private-query");
            case COMMIT -> new TransactionSystemException("private-commit");
        };
        fixture.jdbc.results.add(List.of()); fixture.jdbc.results.add(List.of());
        if (phase == Failure.BEGIN) fixture.transactions.beginFailure = failure;
        else if (phase == Failure.COMMIT) fixture.transactions.commitFailure = failure;
        else fixture.jdbc.failure = failure;
        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.query.period(END, END)));
        assertEquals(phase == Failure.BEGIN ? 0 : 2, fixture.jdbc.ranges.size());
        assertEquals(phase == Failure.BEGIN ? 0 : 1, fixture.samples.get());
        assertEquals(phase == Failure.SECOND_QUERY ? 1 : 0, fixture.transactions.rollbacks); assertFalse(fixture.transactions.active);
    }
    private enum Failure { BEGIN, SECOND_QUERY, COMMIT }
    private record Range(LocalDateTime start, LocalDateTime end) { }
    private static Map<String,Object> row(LocalDate date, long count) { return Map.of("post_date", date, "post_count", count); }

    private static final class Fixture {
        final RecordingTransactions transactions = new RecordingTransactions();
        final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        final AtomicInteger samples = new AtomicInteger();
        final JdbcAdminPostStatisticsQuery query;
        Fixture() {
            var today = new TransactionTemplate(transactions); today.setReadOnly(true); today.setTimeout(3);
            var period = new TransactionTemplate(transactions); period.setReadOnly(true); period.setTimeout(3);
            period.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            query = new JdbcAdminPostStatisticsQuery(jdbc, today, period, new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return new Clock() {
                    @Override public ZoneId getZone() { return zone; }
                    @Override public Clock withZone(ZoneId value) { throw new UnsupportedOperationException(); }
                    @Override public Instant instant() { assertTrue(transactions.active); samples.incrementAndGet(); return Instant.parse("2024-02-29T15:00:00Z"); }
                }; }
                @Override public Instant instant() { throw new AssertionError("The original operation chooses its zone first"); }
            });
        }
    }
    private static final class RecordingJdbc extends JdbcTemplate {
        final RecordingTransactions transactions;
        final Deque<List<Map<String,Object>>> results = new ArrayDeque<>();
        final List<Range> ranges = new ArrayList<>(); final List<String> sql = new ArrayList<>();
        RuntimeException failure;
        RecordingJdbc(RecordingTransactions transactions) { this.transactions = transactions; }
        @Override public <T> List<T> query(String statement, PreparedStatementSetter setter, RowMapper<T> mapper) {
            assertTrue(transactions.active); sql.add(statement);
            try {
                var prepared = mock(PreparedStatement.class);
                Map<Integer,Timestamp> timestamps = new HashMap<>();
                doAnswer(call -> { assertEquals("UTC", ((Calendar)call.getArgument(2)).getTimeZone().getID());
                    timestamps.put(call.getArgument(0), call.getArgument(1)); return null;
                }).when(prepared).setTimestamp(anyInt(), any(Timestamp.class), any(Calendar.class));
                setter.setValues(prepared);
                ranges.add(new Range(timestamps.get(1).toLocalDateTime(), timestamps.get(2).toLocalDateTime()));
                if (failure != null && ranges.size() == 2) throw failure;
                List<T> mapped = new ArrayList<>(); int index = 0;
                for (var row : results.removeFirst()) {
                    var values = mock(ResultSet.class);
                    when(values.getLong("post_count")).thenReturn((Long)row.get("post_count"));
                    when(values.getObject("post_date", LocalDate.class)).thenReturn((LocalDate)row.get("post_date"));
                    when(values.getString("board_type")).thenReturn((String)row.get("board_type"));
                    mapped.add(mapper.mapRow(values, index++));
                }
                return mapped;
            } catch (SQLException exception) { throw new AssertionError(exception); }
        }
    }
    private static final class RecordingTransactions implements PlatformTransactionManager {
        boolean active; int commits, rollbacks; final List<Integer> isolations = new ArrayList<>();
        RuntimeException beginFailure, commitFailure;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertFalse(active); assertTrue(definition.isReadOnly()); assertEquals(3, definition.getTimeout());
            isolations.add(definition.getIsolationLevel()); if (beginFailure != null) throw beginFailure;
            active = true; return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { active = false; commits++; if (commitFailure != null) throw commitFailure; }
        @Override public void rollback(TransactionStatus status) { active = false; rollbacks++; }
    }
}
