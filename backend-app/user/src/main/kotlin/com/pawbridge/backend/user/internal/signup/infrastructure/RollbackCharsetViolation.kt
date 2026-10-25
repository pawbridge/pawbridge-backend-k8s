package com.pawbridge.backend.user.internal.signup.infrastructure

import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Original PostgresqlRollbackCharsetViolation: pawbridge_user ck_rollback_charset_* check failures. */
object RollbackCharsetViolation {
    const val MESSAGE = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."

    @JvmStatic
    fun matches(failure: Throwable): Boolean {
        val visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        val pending = ArrayDeque<Throwable>()
        pending.add(failure)
        while (!pending.isEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            if (current is PSQLException) {
                val details = current.serverErrorMessage
                val constraint = details?.constraint
                if ("23514" == current.sqlState && details != null &&
                    "pawbridge_user" == details.schema &&
                    constraint != null &&
                    constraint.startsWith("ck_rollback_charset_")
                ) {
                    return true
                }
            }
            if (current.cause != null) {
                pending.addLast(current.cause)
            }
            if (current is SQLException && current.nextException != null) {
                pending.addLast(current.nextException)
            }
        }
        return false
    }
}
