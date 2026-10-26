package com.pawbridge.backend.animal.internal.apms.infrastructure

import java.sql.Connection
import java.sql.SQLException

/** Original APMS PostgreSQL session lock, namespace PAWB/key1; no dialect metadata lookup. */
internal object PostgresqlApmsSessionLock {
    private const val NAMESPACE = 0x50415742

    internal enum class Operation { ACQUIRE, RELEASE, OWNED }

    @Throws(SQLException::class)
    internal fun query(connection: Connection, name: String, operation: Operation): Int? {
        if ("pawbridge_animal.apmsAnimalSyncJob" != name) {
            throw IllegalArgumentException("Unknown APMS collection lock")
        }
        val sql = when (operation) {
            Operation.ACQUIRE -> "SELECT CASE WHEN pg_try_advisory_lock($NAMESPACE,?) THEN 1 ELSE 0 END"
            Operation.RELEASE -> "SELECT CASE WHEN pg_advisory_unlock($NAMESPACE,?) THEN 1 ELSE 0 END"
            Operation.OWNED -> "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' " +
                "AND pid=pg_backend_pid() AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) " +
                "AND classid=" + NAMESPACE + " AND objid=CAST(? AS oid) AND objsubid=2 AND granted) THEN 1 ELSE 0 END"
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, 1)
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
