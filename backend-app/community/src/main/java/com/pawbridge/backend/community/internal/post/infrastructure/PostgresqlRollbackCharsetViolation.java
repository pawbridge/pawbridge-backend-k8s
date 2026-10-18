package com.pawbridge.backend.community.internal.post.infrastructure;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

public final class PostgresqlRollbackCharsetViolation {
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
                        && "pawbridge_community".equals(details.getSchema())
                        && details.getConstraint() != null
                        && details.getConstraint().startsWith("ck_rollback_charset_")) {
                    return true;
                }
            }
            if (cause.getCause() != null) {
                pending.addLast(cause.getCause());
            }
            if (cause instanceof SQLException sqlError && sqlError.getNextException() != null) {
                pending.addLast(sqlError.getNextException());
            }
        }
        return false;
    }
}
