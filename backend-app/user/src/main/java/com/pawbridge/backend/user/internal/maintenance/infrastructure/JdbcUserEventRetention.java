package com.pawbridge.backend.user.internal.maintenance.infrastructure;

import com.pawbridge.backend.user.api.UserEventRetention;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcUserEventRetention implements UserEventRetention {
    private static final Logger log = LoggerFactory.getLogger(JdbcUserEventRetention.class);
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JdbcUserEventRetention(JdbcOperations jdbc, TransactionOperations transactions) {
        this(jdbc, transactions, Clock.systemDefaultZone());
    }

    public JdbcUserEventRetention(JdbcOperations jdbc, TransactionOperations transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void cleanupOldOutboxEvents() {
        cleanup("DELETE FROM pawbridge_user.outbox_events WHERE created_at < ?", 7, "outbox");
    }

    @Override
    public void cleanupOldProcessedEvents() {
        cleanup("DELETE FROM pawbridge_user.processed_events WHERE processed_at < ?", 30, "processed");
    }

    private void cleanup(String sql, int days, String kind) {
        transactions.executeWithoutResult(status -> {
            // Original @Transactional boundary starts before the local clock and try block.
            LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);
            try {
                int deleted = jdbc.update(sql, timestamp(cutoff));
                if (deleted > 0) log.info("[CLEANUP] Deleted {} User {} events older than {}", deleted, kind, cutoff);
            } catch (Exception failure) {
                // Keep the original repository-call catch boundary, excluding begin/commit/rollback.
                // Never expose driver messages, SQL parameter values or credentials.
                log.error("[CLEANUP] Failed to cleanup User {} events ({})", kind, failure.getClass().getSimpleName());
            }
        });
    }

    private static SqlValue timestamp(LocalDateTime cutoff) {
        return new SqlValue() {
            public void setValue(PreparedStatement statement, int index) throws SQLException {
                statement.setTimestamp(index, Timestamp.valueOf(cutoff), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            }
            public void cleanup() { }
        };
    }
}
