package com.pawbridge.backend.animal.internal.command.infrastructure.persistence

import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Identifies only this service's temporary rollback charset CHECK violation. */
object PostgresqlRollbackCharsetViolation {
    const val MESSAGE = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."

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
                    "pawbridge_animal" == details.schema &&
                    constraint != null &&
                    constraint.startsWith("ck_rollback_charset_")
                ) {
                    return true
                }
            }
            val nested = cause.cause
            if (nested != null) {
                pending.addLast(nested)
            }
            // JDBC batch errors can carry the constraint metadata in nextException.
            if (cause is SQLException) {
                val next = cause.nextException
                if (next != null) {
                    pending.addLast(next)
                }
            }
        }
        return false
    }
}
