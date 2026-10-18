package com.pawbridge.storeservice.persistence;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identifies only this service's temporary rollback charset CHECK violation. */
public final class PostgresqlRollbackCharsetViolation {
    public static final String MESSAGE = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.";

    private PostgresqlRollbackCharsetViolation() {
    }

    public static boolean matches(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(error);
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) {
                continue;
            }
            if (cause instanceof PSQLException sqlError) {
                ServerErrorMessage details = sqlError.getServerErrorMessage();
                if ("23514".equals(sqlError.getSQLState()) && details != null
                        && "pawbridge_store".equals(details.getSchema())
                        && details.getConstraint() != null
                        && details.getConstraint().startsWith("ck_rollback_charset_")) {
                    return true;
                }
            }
            if (cause.getCause() != null) {
                pending.addLast(cause.getCause());
            }
            // JDBC batch errors can carry the constraint metadata in nextException.
            if (cause instanceof SQLException sqlError && sqlError.getNextException() != null) {
                pending.addLast(sqlError.getNextException());
            }
        }
        return false;
    }
}
