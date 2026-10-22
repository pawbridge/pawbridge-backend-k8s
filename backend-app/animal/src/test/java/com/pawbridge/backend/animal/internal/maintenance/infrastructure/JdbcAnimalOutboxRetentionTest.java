package com.pawbridge.backend.animal.internal.maintenance.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcAnimalOutboxRetentionTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final List<String> events = new ArrayList<>();
    private final AtomicInteger clockReads = new AtomicInteger();
    private final RuntimeException failure = new IllegalStateException("synthetic retention failure");
    private final Instant instant = Instant.parse("2026-10-20T15:00:00.123456789Z");
    private final ZoneId zone = ZoneId.of("Asia/Seoul");
    private boolean active;
    private String failAt;
    private String sql;
    private JdbcAnimalOutboxRetention retention;

    @BeforeEach
    void prepare() throws Exception {
        Clock clock = new Clock() {
            public ZoneId getZone() { return zone; }
            public Clock withZone(ZoneId next) { return Clock.fixed(instant, next); }
            public Instant instant() { assertTrue(active); clockReads.incrementAndGet(); events.add("clock"); return instant; }
        };
        TransactionOperations transaction = new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) {
                events.add("begin"); if ("begin".equals(failAt)) throw failure;
                active = true;
                try {
                    T result = callback.doInTransaction(new SimpleTransactionStatus()); events.add("commit");
                    if ("commit".equals(failAt)) throw failure;
                    return result;
                } finally { active = false; }
            }
        };
        retention = new JdbcAnimalOutboxRetention(jdbc, transaction, clock);
        when(jdbc.update(anyString(), any(SqlValue.class))).thenAnswer(call -> {
            assertTrue(active); events.add("delete"); sql = call.getArgument(0);
            call.getArgument(1, SqlValue.class).setValue(statement, 1);
            if ("delete".equals(failAt)) throw failure;
            return 4;
        });
    }

    @Test
    void given__a_local_jvm_clock__when__cleaning_the_outbox__then__sample_after_begin_and_delete_every_row_strictly_before_the_seven_day_cutoff_regardless_of_delivery() throws Exception {
        assertEquals(4, retention.cleanupOldOutboxEvents());
        assertEquals("DELETE FROM pawbridge_animal.outbox_events WHERE created_at < ?", sql);
        var time = ArgumentCaptor.forClass(Timestamp.class); var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(1), time.capture(), calendar.capture());
        assertEquals(LocalDateTime.ofInstant(instant, zone).minusDays(7), time.getValue().toLocalDateTime());
        assertEquals("UTC", calendar.getValue().getTimeZone().getID());
        assertEquals(1, clockReads.get()); assertEquals(List.of("begin", "clock", "delete", "commit"), events);
        assertFalse(active); verify(jdbc).update(eq(sql), any(SqlValue.class)); verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest @ValueSource(strings = {"delete", "commit"})
    void given__delete_or_commit_failure__when__retaining_events__then__propagate_without_fabricated_success_or_followup_cleanup(String stage) {
        failAt = stage;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> retention.cleanupOldOutboxEvents()));
        assertEquals(1, clockReads.get()); assertFalse(active);
        if (stage.equals("delete")) assertFalse(events.contains("commit"));
    }

    @Test
    void given__transaction_begin_failure__when__cleaning_outbox__then__sample_no_clock_and_execute_no_delete() {
        failAt = "begin";
        assertSame(failure, assertThrows(IllegalStateException.class, () -> retention.cleanupOldOutboxEvents()));
        assertEquals(0, clockReads.get()); verifyNoInteractions(jdbc);
    }
}
