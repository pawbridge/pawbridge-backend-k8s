package com.pawbridge.backend.user.internal.statistics.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcSignupStatisticsQueryTest {
    @Test
    void given__sparse_daily_rows_and_long_count__when__reading__then__return_raw_rows_without_zero_fill_or_clock_and_bind_local_dates() {
        var fixture = new Fixture(Instant.EPOCH);
        LocalDate start = LocalDate.of(2024, 2, 27), end = LocalDate.of(2024, 2, 29);
        var raw = List.of(new DailySignupStats(start, Long.MAX_VALUE), new DailySignupStats(end, 2L));
        fixture.jdbc.results.add(raw);

        assertEquals(raw, fixture.query.daily(start, end));

        assertEquals(List.of(new Range(start, end)), fixture.jdbc.ranges);
        assertEquals(0, fixture.clock.samples.get());
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        assertTrue(fixture.jdbc.sql.contains("CAST(created_at + INTERVAL '9 hours' AS DATE) BETWEEN ? AND ?"));
        assertTrue(fixture.jdbc.sql.contains("GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)"));
        assertTrue(fixture.jdbc.sql.contains("ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)"));
        assertFalse(fixture.jdbc.sql.contains("AT TIME ZONE"));
    }

    @Test
    void given__reversed_dates__when__reading_daily__then__pass_the_range_to_sql_and_return_its_empty_result() {
        var fixture = new Fixture(Instant.EPOCH);
        LocalDate start = LocalDate.of(2024, 3, 1), end = start.minusDays(1);
        fixture.jdbc.results.add(List.of());

        assertEquals(List.of(), fixture.query.daily(start, end));

        assertEquals(List.of(new Range(start, end)), fixture.jdbc.ranges);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @MethodSource("seoulDays")
    void given__a_seoul_day_boundary_and_independent_query_counts__when__reading_periods__then__sample_once_inside_one_read_only_transaction_and_fill_each_inclusive_range(
            Instant instant, LocalDate today) {
        var fixture = new Fixture(instant);
        fixture.jdbc.results.add(List.of(new DailySignupStats(today, 10L)));
        fixture.jdbc.results.add(List.of(new DailySignupStats(today, 11L)));
        fixture.jdbc.results.add(List.of(new DailySignupStats(today, 12L)));
        fixture.jdbc.results.add(List.of(new DailySignupStats(today, 13L)));

        var result = fixture.query.periods();

        assertEquals(1, fixture.clock.samples.get());
        assertEquals(List.of(new Range(today, today), new Range(today.minusDays(6), today),
                new Range(today.minusDays(29), today), new Range(today.withDayOfMonth(1), today)), fixture.jdbc.ranges);
        assertEquals(List.of(new DailySignupStats(today, 10L)), result.today());
        assertEquals(7, result.last7Days().size());
        assertEquals(30, result.last30Days().size());
        assertEquals(today.getDayOfMonth(), result.thisMonth().size());
        assertEquals(new DailySignupStats(today.minusDays(6), 0L), result.last7Days().getFirst());
        assertEquals(new DailySignupStats(today.minusDays(29), 0L), result.last30Days().getFirst());
        assertEquals(new DailySignupStats(today, 11L), result.last7Days().getLast());
        assertEquals(new DailySignupStats(today, 12L), result.last30Days().getLast());
        assertEquals(new DailySignupStats(today, 13L), result.thisMonth().getLast());
        assertEquals(1, fixture.transactions.starts);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    static Stream<Arguments> seoulDays() {
        return Stream.of(Arguments.of(Instant.parse("2024-02-29T14:59:59.999999Z"), LocalDate.of(2024, 2, 29)),
                Arguments.of(Instant.parse("2024-02-29T15:00:00Z"), LocalDate.of(2024, 3, 1)));
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__begin_second_query_or_commit_failure__when__reading_periods__then__propagate_the_original_failure_without_partial_response_or_retry(FailurePhase phase) {
        var fixture = new Fixture(Instant.EPOCH);
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic-private-begin");
            case SECOND_QUERY -> new DataAccessResourceFailureException("synthetic-private-query");
            case COMMIT -> new TransactionSystemException("synthetic-private-commit");
        };
        for (int index = 0; index < 4; index++) fixture.jdbc.results.add(List.of());
        if (phase == FailurePhase.BEGIN) fixture.transactions.beginFailure = failure;
        else if (phase == FailurePhase.SECOND_QUERY) fixture.jdbc.failure = failure;
        else fixture.transactions.commitFailure = failure;

        assertSame(failure, assertThrows(RuntimeException.class, fixture.query::periods));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : phase == FailurePhase.SECOND_QUERY ? 2 : 4,
                fixture.jdbc.ranges.size());
        assertEquals(phase == FailurePhase.SECOND_QUERY ? 1 : 0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    private enum FailurePhase { BEGIN, SECOND_QUERY, COMMIT }
    private record Range(LocalDate start, LocalDate end) { }

    private static final class Fixture {
        final RecordingTransactions transactions = new RecordingTransactions();
        final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        final CountingClock clock;
        final JdbcSignupStatisticsQuery query;
        Fixture(Instant instant) {
            clock = new CountingClock(instant, ZoneOffset.UTC, transactions, new AtomicInteger());
            var readOnly = new TransactionTemplate(transactions);
            readOnly.setReadOnly(true);
            readOnly.setTimeout(3);
            query = new JdbcSignupStatisticsQuery(jdbc, readOnly, clock);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        final RecordingTransactions transactions;
        final Deque<List<DailySignupStats>> results = new ArrayDeque<>();
        final List<Range> ranges = new ArrayList<>();
        String sql;
        RuntimeException failure;
        RecordingJdbc(RecordingTransactions transactions) { this.transactions = transactions; }
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
            assertTrue(transactions.active);
            assertEquals(2, arguments.length);
            this.sql = sql;
            ranges.add(new Range((LocalDate) arguments[0], (LocalDate) arguments[1]));
            if (failure != null && ranges.size() == 2) throw failure;
            List<T> mapped = new ArrayList<>();
            try {
                int index = 0;
                for (DailySignupStats row : results.removeFirst()) {
                    ResultSet rows = mock(ResultSet.class);
                    when(rows.getObject("signup_date", LocalDate.class)).thenReturn(row.date());
                    when(rows.getLong("signup_count")).thenReturn(row.count());
                    mapped.add(mapper.mapRow(rows, index++));
                }
            } catch (SQLException failure) { throw new AssertionError(failure); }
            return mapped;
        }
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        boolean active;
        int starts, commits, rollbacks;
        RuntimeException beginFailure, commitFailure;
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertFalse(active);
            assertTrue(definition.isReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.getIsolationLevel());
            assertEquals(3, definition.getTimeout());
            starts++;
            if (beginFailure != null) throw beginFailure;
            active = true;
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) {
            active = false; commits++;
            if (commitFailure != null) throw commitFailure;
        }
        @Override public void rollback(TransactionStatus status) { active = false; rollbacks++; }
    }

    private static final class CountingClock extends Clock {
        final Instant value;
        final ZoneId zone;
        final RecordingTransactions transactions;
        final AtomicInteger samples;
        CountingClock(Instant value, ZoneId zone, RecordingTransactions transactions, AtomicInteger samples) {
            this.value = value; this.zone = zone; this.transactions = transactions; this.samples = samples;
        }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new CountingClock(value, zone, transactions, samples); }
        @Override public Instant instant() {
            assertTrue(transactions.active);
            samples.incrementAndGet();
            return value;
        }
    }
}
