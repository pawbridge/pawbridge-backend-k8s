package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersInvalidException
import org.springframework.batch.core.launch.JobLauncher
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import javax.sql.DataSource

@ExtendWith(MockitoExtension::class)
class ApmsBatchRunnerTest {
    @Mock
    private lateinit var dataSource: DataSource

    @Mock
    private lateinit var connection: Connection

    @Mock
    private lateinit var acquireStatement: PreparedStatement

    @Mock
    private lateinit var releaseStatement: PreparedStatement

    @Mock
    private lateinit var acquireResult: ResultSet

    @Mock
    private lateinit var releaseResult: ResultSet

    @Mock
    private lateinit var executionRecovery: ApmsBatchExecutionRecovery

    @Mock
    private lateinit var launcher: JobLauncher

    @Mock
    private lateinit var job: Job

    @Mock
    private lateinit var planFactory: ApmsSyncPlanFactory

    private lateinit var runner: ApmsBatchRunner

    @BeforeEach
    fun setUp() {
        runner = ApmsBatchRunner(dataSource, executionRecovery, launcher, job, planFactory)
        `when`(dataSource.connection).thenReturn(connection)
    }

    @Test
    fun givenLockOwnedElsewhere__whenRun__thenRejectWithoutLaunchingOrReleasing() {
        acquired(0)

        assertThatThrownBy { runner.run() }.isInstanceOf(ApmsBatchRunner.AlreadyRunningException::class.java)

        verifyNoInteractions(executionRecovery, launcher)
        verify(connection, never()).prepareStatement(RELEASE)
        verify(connection).close()
    }

    @Test
    fun givenIndeterminateLock__whenRun__thenAbortWithoutLaunching() {
        acquired(null)

        assertThatThrownBy { runner.run() }.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)

        verify(connection).abort(any())
        verify(connection).close()
        verifyNoInteractions(executionRecovery, launcher)
    }

    @Test
    fun givenRunningMetadata__whenRun__thenReleaseAndRequireRecoveryCheck() {
        acquired(1)
        released(1)
        `when`(job.name).thenReturn("apmsAnimalSyncJob")
        `when`(executionRecovery.recoverOrReject("apmsAnimalSyncJob"))
            .thenThrow(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException(1L))

        assertThatThrownBy { runner.run() }.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)

        verifyNoInteractions(launcher)
        verify(releaseStatement).executeQuery()
        verify(connection).close()
    }

    @Test
    fun givenLaunchFailure__whenRun__thenReleaseSameConnection() {
        allowedStart()
        released(1)
        `when`(launcher.run(eq(job), any())).thenThrow(JobParametersInvalidException("invalid request"))

        assertThatThrownBy { runner.run() }.isInstanceOf(JobParametersInvalidException::class.java)

        val order = inOrder(launcher, releaseStatement, connection)
        order.verify(launcher).run(eq(job), any())
        order.verify(releaseStatement).executeQuery()
        order.verify(connection).close()
        verify(dataSource).connection
    }

    @Test
    fun givenSuccessfulLaunch__whenRun__thenReleaseAfterSynchronousCompletion() {
        allowedStart()
        released(1)
        val execution = JobExecution(7L)
        `when`(launcher.run(eq(job), any())).thenReturn(execution)

        assertThat(runner.run()).isSameAs(execution)

        val order = inOrder(acquireStatement, executionRecovery, launcher, releaseStatement, connection)
        order.verify(acquireStatement).executeQuery()
        order.verify(executionRecovery).recoverOrReject("apmsAnimalSyncJob")
        order.verify(launcher).run(
            eq(job),
            argThat(
                ArgumentMatcher<JobParameters> { parameters ->
                    parameters.getString("requestId") != null &&
                        ApmsSyncPlan.from(parameters).end == LocalDate.of(2026, 9, 10)
                },
            ),
        )
        order.verify(releaseStatement).executeQuery()
        order.verify(connection).close()
        verify(connection, never()).abort(any())
        verify(acquireStatement).setInt(1, 1)
        verify(acquireStatement).setQueryTimeout(5)
        verify(releaseStatement).setInt(1, 1)
        verify(connection, never()).getMetaData()
        verify(dataSource).connection
    }

    @Test
    fun givenReleaseNotOwned__whenRun__thenAbortAndNeverReportSuccess() {
        allowedStart()
        released(0)
        `when`(launcher.run(eq(job), any())).thenReturn(JobExecution(7L))

        assertThatThrownBy { runner.run() }.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)

        verify(connection).abort(any())
        verify(connection).close()
    }

    @Test
    fun givenReleaseConnectionFailure__whenRun__thenAbortAndFailClosed() {
        allowedStart()
        `when`(connection.prepareStatement(RELEASE)).thenThrow(SQLException("test failure"))
        `when`(launcher.run(eq(job), any())).thenReturn(JobExecution(7L))

        assertThatThrownBy { runner.run() }.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)

        verify(connection).abort(any())
        verify(connection).close()
    }

    @Test
    fun givenAcquisitionConnectionFailure__whenRun__thenAbortWithoutLaunching() {
        `when`(connection.prepareStatement(ACQUIRE)).thenThrow(SQLException("test failure"))

        assertThatThrownBy { runner.run() }.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)

        verify(connection).abort(any())
        verify(connection).close()
        verifyNoInteractions(executionRecovery, launcher)
    }

    @Test
    fun givenPlanningFailure__whenRun__thenReleaseLockWithoutLaunching() {
        allowedStart()
        released(1)
        `when`(planFactory.create("apmsAnimalSyncJob")).thenThrow(IllegalStateException("history unavailable"))

        assertThatThrownBy { runner.run() }.hasMessage("history unavailable")

        verifyNoInteractions(launcher)
        verify(releaseStatement).executeQuery()
        verify(connection).close()
    }

    private fun allowedStart() {
        acquired(1)
        val day = LocalDate.of(2026, 9, 10)
        `when`(planFactory.create("apmsAnimalSyncJob")).thenReturn(
            ApmsSyncPlan(day.minusDays(30), day.minusDays(30).withDayOfMonth(1), day.minusDays(30), day),
        )
        `when`(job.name).thenReturn("apmsAnimalSyncJob")
        `when`(executionRecovery.recoverOrReject("apmsAnimalSyncJob")).thenReturn(0)
    }

    private fun acquired(value: Int?) {
        `when`(connection.prepareStatement(ACQUIRE)).thenReturn(acquireStatement)
        `when`(acquireStatement.executeQuery()).thenReturn(acquireResult)
        `when`(acquireResult.next()).thenReturn(true)
        `when`(acquireResult.getObject(1)).thenReturn(value)
    }

    private fun released(value: Int?) {
        `when`(connection.prepareStatement(RELEASE)).thenReturn(releaseStatement)
        `when`(releaseStatement.executeQuery()).thenReturn(releaseResult)
        `when`(releaseResult.next()).thenReturn(true)
        `when`(releaseResult.getObject(1)).thenReturn(value)
    }

    companion object {
        private const val ACQUIRE = "SELECT CASE WHEN pg_try_advisory_lock(1346459458,?) THEN 1 ELSE 0 END"
        private const val RELEASE = "SELECT CASE WHEN pg_advisory_unlock(1346459458,?) THEN 1 ELSE 0 END"
    }
}
