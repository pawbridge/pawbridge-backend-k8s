package com.pawbridge.backend.animal.internal.maintenance.infrastructure;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcAnimalOutboxRetention {
    // Original deleteByCreatedAtBefore: every row older than seven days, whether or not it was delivered.
    private static final String OUTBOX = "DELETE FROM pawbridge_animal.outbox_events WHERE created_at < ?";

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JdbcAnimalOutboxRetention(JdbcOperations jdbc, TransactionOperations transactions) {
        this(jdbc, transactions, Clock.systemDefaultZone());
    }

    public JdbcAnimalOutboxRetention(JdbcOperations jdbc, TransactionOperations transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public int cleanupOldOutboxEvents() {
        return transactions.execute(status -> jdbc.update(OUTBOX, timestamp(LocalDateTime.now(clock).minusDays(7))));
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
