package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import java.sql.Connection
import java.sql.SQLException

/** Original collection PostgreSQL session lock, namespace PAWB/key1 shared with APMS animal collection. */
internal object PostgresqlShelterDirectorySessionLock {
    private const val NAMESPACE = 0x50415742

    enum class Operation { ACQUIRE, RELEASE }

    @JvmStatic
    @Throws(SQLException::class)
    fun query(connection: Connection, name: String, operation: Operation): Int? {
        if ("pawbridge_animal.apmsAnimalSyncJob" != name) {
            throw IllegalArgumentException("Unknown collection lock")
        }
        val sql = when (operation) {
            Operation.ACQUIRE -> "SELECT CASE WHEN pg_try_advisory_lock($NAMESPACE,?) THEN 1 ELSE 0 END"
            Operation.RELEASE -> "SELECT CASE WHEN pg_advisory_unlock($NAMESPACE,?) THEN 1 ELSE 0 END"
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
