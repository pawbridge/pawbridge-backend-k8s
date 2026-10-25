package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.PostgresqlApmsSessionLock.Operation
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.DataSource

/** Serializes requests across application instances using a dedicated database session. */
class ApmsBatchRunner(
    private val dataSource: DataSource,
    private val executionRecovery: ApmsBatchExecutionRecovery,
    private val jobLauncher: JobLauncher,
    private val apmsAnimalSyncJob: Job,
    private val planFactory: ApmsSyncPlanFactory,
) {

    @Throws(Exception::class)
    fun run(): JobExecution {
        try {
            return dataSource.connection.use { connection -> runWithLock(connection) }
        } catch (exception: SQLException) {
            throw UnavailableException()
        }
    }

    private fun runWithLock(connection: Connection): JobExecution {
        val acquired: Int?
        try {
            acquired = PostgresqlApmsSessionLock.query(connection, LOCK_NAME, Operation.ACQUIRE)
        } catch (exception: SQLException) {
            discard(connection)
            throw UnavailableException()
        }
        if (acquired == null || (acquired != 0 && acquired != 1)) {
            discard(connection)
            throw UnavailableException()
        }
        if (acquired == 0) {
            throw AlreadyRunningException()
        }
        try {
            try {
                executionRecovery.recoverOrReject(apmsAnimalSyncJob.name)
            } catch (exception: RuntimeException) {
                throw UnavailableException()
            }
            // The explicitly qualified APMS launcher holds this thread until completion.
            return jobLauncher.run(
                apmsAnimalSyncJob,
                JobParametersBuilder(planFactory.create(apmsAnimalSyncJob.name).parameters())
                    .addString("requestId", UUID.randomUUID().toString())
                    .toJobParameters(),
            )
        } finally {
            release(connection)
        }
    }

    private fun release(connection: Connection) {
        try {
            if (PostgresqlApmsSessionLock.query(connection, LOCK_NAME, Operation.RELEASE) == 1) {
                return
            }
        } catch (exception: SQLException) {
            // Never return a connection with an uncertain named lock to the pool.
        }
        discard(connection)
        throw UnavailableException()
    }

    private fun discard(connection: Connection) {
        try {
            connection.abort(Executor { command -> command.run() })
        } catch (exception: SQLException) {
            throw UnavailableException()
        }
    }

    class AlreadyRunningException : RuntimeException("APMS batch is already running")

    class UnavailableException :
        RuntimeException("APMS execution guard is unavailable; check running job metadata")

    private companion object {
        private const val LOCK_NAME = "pawbridge_animal.apmsAnimalSyncJob"
    }
}
