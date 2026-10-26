package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.PostgresqlShelterDirectorySessionLock.Operation
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import javax.sql.DataSource

class ShelterDirectoryCollector(
    private val source: DataSource,
    private val client: ShelterDirectoryClient,
    private val store: ShelterDirectoryStore,
) {

    @Throws(Exception::class)
    fun collect(): Int {
        source.connection.use { connection ->
            val acquired: Boolean
            try {
                acquired = ONE == PostgresqlShelterDirectorySessionLock.query(connection, LOCK, Operation.ACQUIRE)
            } catch (exception: Exception) {
                connection.abort(DIRECT_EXECUTOR)
                throw exception
            }
            if (!acquired) {
                throw IllegalStateException("SHELTER_DIRECTORY_BUSY")
            }
            try {
                val rows = client.collect()
                connection.autoCommit = false
                try {
                    store.save(connection, rows, LocalDateTime.now(ZoneOffset.UTC))
                    connection.commit()
                    connection.autoCommit = true
                    return rows.size
                } catch (exception: Exception) {
                    try {
                        connection.rollback()
                        connection.autoCommit = true
                    } catch (rollbackFailure: Exception) {
                        connection.abort(DIRECT_EXECUTOR)
                    }
                    throw exception
                }
            } finally {
                try {
                    if (ONE != PostgresqlShelterDirectorySessionLock.query(connection, LOCK, Operation.RELEASE)) {
                        connection.abort(DIRECT_EXECUTOR)
                    }
                } catch (exception: Exception) {
                    connection.abort(DIRECT_EXECUTOR)
                }
            }
        }
    }

    companion object {
        // Same lock as ApmsBatchRunner: preserves the existing animal fallback insert contract.
        private const val LOCK = "pawbridge_animal.apmsAnimalSyncJob"
        private const val ONE = 1

        // Java passed Runnable::run: the abort task runs on the calling thread.
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
