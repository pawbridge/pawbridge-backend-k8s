package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import java.sql.Connection
import java.sql.SQLException

/** Original travel-only PostgreSQL same-session advisory lock; independent of commit/rollback. */
internal object PostgresqlTravelSessionLock {
    private const val NAMESPACE = 0x50415742

    enum class Operation { ACQUIRE, RELEASE, OWNED }

    @JvmStatic
    @Throws(SQLException::class)
    fun query(connection: Connection, name: String, operation: Operation): Int? {
        if ("pawbridge_animal.petTravelCollection" != name) {
            throw IllegalArgumentException("Unknown collection lock")
        }
        val sql = when (operation) {
            Operation.ACQUIRE -> "SELECT CASE WHEN pg_try_advisory_lock($NAMESPACE,?) THEN 1 ELSE 0 END"
            Operation.RELEASE -> "SELECT CASE WHEN pg_advisory_unlock($NAMESPACE,?) THEN 1 ELSE 0 END"
            Operation.OWNED -> "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' " +
                "AND pid=pg_backend_pid() AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) " +
                "AND classid=" + NAMESPACE + " AND objid=CAST(? AS oid) AND objsubid=2 AND granted) THEN 1 ELSE 0 END"
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, 2)
            statement.queryTimeout = 5
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    return null
                }
                val value = result.getObject(1) as Number?
                return value?.toInt()
            }
        }
    }
}
