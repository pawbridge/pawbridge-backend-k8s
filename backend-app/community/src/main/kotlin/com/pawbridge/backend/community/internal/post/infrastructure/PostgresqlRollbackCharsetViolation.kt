package com.pawbridge.backend.community.internal.post.infrastructure

import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import org.postgresql.util.PSQLException

object PostgresqlRollbackCharsetViolation {

    @JvmStatic
    fun matches(error: Throwable): Boolean {
        val visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        val pending = ArrayDeque<Throwable>()
        pending.add(error)
        while (!pending.isEmpty()) {
            val cause = pending.removeFirst()
            if (!visited.add(cause)) {
                continue
            }
            if (cause is PSQLException) {
                val details = cause.serverErrorMessage
                val constraint = details?.constraint
                if ("23514" == cause.sqlState &&
                    details != null &&
                    "pawbridge_community" == details.schema &&
                    constraint != null &&
                    constraint.startsWith("ck_rollback_charset_")
                ) {
                    return true
                }
            }
            if (cause.cause != null) {
                pending.addLast(cause.cause)
            }
            if (cause is SQLException && cause.nextException != null) {
                pending.addLast(cause.nextException)
            }
        }
        return false
    }
}
