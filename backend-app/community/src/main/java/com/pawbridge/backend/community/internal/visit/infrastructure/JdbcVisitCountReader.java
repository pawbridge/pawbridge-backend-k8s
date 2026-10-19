package com.pawbridge.backend.community.internal.visit.infrastructure;

import com.pawbridge.backend.community.internal.visit.application.VisitCountReader;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcVisitCountReader implements VisitCountReader {
    private static final String READ_COUNT_SQL = """
            SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date = ?
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcVisitCountReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public long count(LocalDate day) {
        return readOnlyTransaction.execute(status -> {
            List<Long> rows = jdbc.queryForList(READ_COUNT_SQL, Long.class, Date.valueOf(day));
            return rows.isEmpty() ? 0L : rows.get(0);
        });
    }
}
