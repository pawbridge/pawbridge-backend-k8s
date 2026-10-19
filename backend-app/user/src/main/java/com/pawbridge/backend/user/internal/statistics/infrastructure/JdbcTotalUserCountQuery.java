package com.pawbridge.backend.user.internal.statistics.infrastructure;

import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcTotalUserCountQuery implements TotalUserCountQuery {
    private static final String COUNT_USERS_SQL = "SELECT count(user_id) FROM pawbridge_user.users";

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcTotalUserCountQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public long count() {
        return readOnlyTransaction.execute(status -> jdbc.queryForObject(COUNT_USERS_SQL, Long.class));
    }
}
