package com.pawbridge.backend.user.internal.maintenance.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcUserEventRetentionTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final List<String> order = new ArrayList<>();
    private final RuntimeException failure = new IllegalStateException("synthetic failure");
    private final Instant instant = Instant.parse("2026-10-20T15:00:00.123456789Z");
    private final ZoneId zone = ZoneId.of("Asia/Seoul");
    private boolean clockFails;
    private JdbcUserEventRetention retention;

    @BeforeEach
    void prepare() throws Exception {
        when(manager.getTransaction(any())).thenAnswer(call -> { order.add("begin"); return status; });
        doAnswer(call -> { order.add("commit"); return null; }).when(manager).commit(status);
        doAnswer(call -> { order.add("rollback"); return null; }).when(manager).rollback(status);
        Clock clock = new Clock() {
            public ZoneId getZone() { return zone; }
            public Clock withZone(ZoneId next) { return Clock.fixed(instant, next); }
            public Instant instant() { order.add("clock"); if (clockFails) throw failure; return instant; }
        };
        retention = new JdbcUserEventRetention(jdbc, new TransactionTemplate(manager), clock);
        when(jdbc.update(anyString(), any(SqlValue.class))).thenAnswer(call -> {
            order.add("delete"); call.getArgument(1, SqlValue.class).setValue(statement, 1); return 4;
        });
    }

    private void clean(String kind) {
        if (kind.equals("outbox")) retention.cleanupOldOutboxEvents(); else retention.cleanupOldProcessedEvents();
    }

    @ParameterizedTest @ValueSource(strings = {"outbox", "processed"})
    void given__local_clock__when__cleaning__then__delete_strictly_before_seven_or_thirty_days_inside_transaction(String kind) throws Exception {
        clean(kind);
        String table = kind.equals("outbox") ? "outbox_events" : "processed_events";
        String column = kind.equals("outbox") ? "created_at" : "processed_at";
        verify(jdbc).update(eq("DELETE FROM pawbridge_user." + table + " WHERE " + column + " < ?"), any(SqlValue.class));
        var time = ArgumentCaptor.forClass(Timestamp.class); var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(1), time.capture(), calendar.capture());
        assertEquals(LocalDateTime.ofInstant(instant, zone).minusDays(kind.equals("outbox") ? 7 : 30), time.getValue().toLocalDateTime());
        assertEquals("UTC", calendar.getValue().getTimeZone().getID());
        assertEquals(List.of("begin", "clock", "delete", "commit"), order);
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest @ValueSource(strings = {"outbox", "processed"})
    void given__delete_failure__when__cleaning__then__catch_only_repository_boundary_and_leave_transaction_completion_to_manager(String kind) {
        doThrow(failure).when(jdbc).update(anyString(), any(SqlValue.class));
        assertDoesNotThrow(() -> clean(kind));
        verify(manager).commit(status); verify(manager, never()).rollback(any());
        verify(jdbc, times(1)).update(anyString(), any(SqlValue.class));
    }

    @ParameterizedTest @ValueSource(strings = {"outbox", "processed"})
    void given__begin_failure__when__cleaning__then__propagate_before_clock_or_delete(String kind) {
        doThrow(failure).when(manager).getTransaction(any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> clean(kind)));
        assertEquals(List.of(), order); verifyNoInteractions(jdbc);
        verify(manager, never()).commit(any()); verify(manager, never()).rollback(any());
    }

    @ParameterizedTest @ValueSource(strings = {"outbox", "processed"})
    void given__commit_failure_after_caught_delete_failure__when__cleaning__then__propagate_commit_failure(String kind) {
        doThrow(new IllegalArgumentException("synthetic delete failure")).when(jdbc).update(anyString(), any(SqlValue.class));
        doThrow(failure).when(manager).commit(status);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> clean(kind)));
        verify(manager).commit(status); verify(jdbc, times(1)).update(anyString(), any(SqlValue.class));
    }

    @ParameterizedTest @ValueSource(strings = {"outbox", "processed"})
    void given__clock_failure__when__cleaning__then__rollback_and_propagate_outside_repository_catch(String kind) {
        clockFails = true;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> clean(kind)));
        assertEquals(List.of("begin", "clock", "rollback"), order);
        verifyNoInteractions(jdbc); verify(manager, never()).commit(any());
    }

    @ParameterizedTest @ValueSource(strings = {"outbox", "processed"})
    void given__rollback_failure__when__clock_fails__then__propagate_transaction_failure(String kind) {
        clockFails = true;
        var rollbackFailure = new IllegalStateException("synthetic rollback failure");
        doThrow(rollbackFailure).when(manager).rollback(status);
        assertSame(rollbackFailure, assertThrows(IllegalStateException.class, () -> clean(kind)));
        verifyNoInteractions(jdbc); verify(manager, never()).commit(any());
    }
}
