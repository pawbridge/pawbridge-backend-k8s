package com.pawbridge.backend.user.internal.signup.infrastructure;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Original PostgresqlRollbackCharsetViolation: pawbridge_user ck_rollback_charset_* check failures. */
public final class RollbackCharsetViolation {
    public static final String MESSAGE = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.";

    private RollbackCharsetViolation() {
    }

    public static boolean matches(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof PSQLException sqlError) {
                ServerErrorMessage details = sqlError.getServerErrorMessage();
                if ("23514".equals(sqlError.getSQLState()) && details != null
                        && "pawbridge_user".equals(details.getSchema())
                        && details.getConstraint() != null
                        && details.getConstraint().startsWith("ck_rollback_charset_")) {
                    return true;
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            if (current instanceof SQLException sqlError && sqlError.getNextException() != null) {
                pending.addLast(sqlError.getNextException());
            }
        }
        return false;
    }
}
