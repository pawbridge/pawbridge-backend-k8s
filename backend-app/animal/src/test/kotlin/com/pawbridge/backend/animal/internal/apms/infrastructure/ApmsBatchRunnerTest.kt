package com.pawbridge.backend.animal.internal.apms.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

class ApmsBatchRunnerTest {
    private val dataSource = mockk<DataSource>()
    private val connection = mockk<Connection>()
    private val acquireStatement = mockk<PreparedStatement>()
    private val releaseStatement = mockk<PreparedStatement>()
    private val acquireResult = mockk<ResultSet>()
    private val releaseResult = mockk<ResultSet>()
    private val executionRecovery = mockk<ApmsBatchExecutionRecovery>()
    private val launcher = mockk<JobLauncher>()
    private val job = mockk<Job>()
    private val planFactory = mockk<ApmsSyncPlanFactory>()

    private lateinit var runner: ApmsBatchRunner

    @BeforeEach
    fun setUp() {
        runner = ApmsBatchRunner(dataSource, executionRecovery, launcher, job, planFactory)
    }

    @Test
    fun `잠금을 다른 곳이 쥐고 있을 때__실행하면__작업 시작과 잠금 해제 없이 거부한다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 0
        justRun { acquireResult.close() }

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(ApmsBatchRunner.AlreadyRunningException::class.java)
        confirmVerified(executionRecovery, launcher)
        verify(exactly = 0) { connection.prepareStatement(RELEASE) }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `잠금 결과가 불확실할 때__실행하면__작업 시작 없이 연결을 폐기한다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        justRun { connection.abort(any()) }
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns null
        justRun { acquireResult.close() }

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)
        verify(exactly = 1) { connection.abort(any()) }
        verify(exactly = 1) { connection.close() }
        confirmVerified(executionRecovery, launcher)
    }

    @Test
    fun `실행 중인 메타데이터가 있을 때__실행하면__잠금을 풀고 복구 확인을 요구한다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        every { connection.prepareStatement(RELEASE) } returns releaseStatement
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 1
        justRun { acquireResult.close() }
        justRun { releaseStatement.setInt(any(), any()) }
        justRun { releaseStatement.setQueryTimeout(any()) }
        every { releaseStatement.executeQuery() } returns releaseResult
        justRun { releaseStatement.close() }
        every { releaseResult.next() } returns true
        every { releaseResult.getObject(any<Int>()) } returns 1
        justRun { releaseResult.close() }
        every { job.name } returns "apmsAnimalSyncJob"
        every { executionRecovery.recoverOrReject(any()) } throws ApmsBatchExecutionRecovery.ActiveExecutionMetadataException(1L)

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)
        confirmVerified(launcher)
        verify(exactly = 1) { executionRecovery.recoverOrReject("apmsAnimalSyncJob") }
        verify(exactly = 1) { releaseStatement.executeQuery() }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `작업 시작이 실패했을 때__실행하면__같은 연결로 잠금을 푼다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        every { connection.prepareStatement(RELEASE) } returns releaseStatement
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 1
        justRun { acquireResult.close() }
        justRun { releaseStatement.setInt(any(), any()) }
        justRun { releaseStatement.setQueryTimeout(any()) }
        every { releaseStatement.executeQuery() } returns releaseResult
        justRun { releaseStatement.close() }
        every { releaseResult.next() } returns true
        every { releaseResult.getObject(any<Int>()) } returns 1
        justRun { releaseResult.close() }
        every { planFactory.create(any()) } returns SYNC_PLAN
        every { job.name } returns "apmsAnimalSyncJob"
        every { executionRecovery.recoverOrReject(any()) } returns 0
        every { launcher.run(any(), any()) } throws JobParametersInvalidException("invalid request")

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(JobParametersInvalidException::class.java)
        verifyOrder {
            launcher.run(job, any())
            releaseStatement.executeQuery()
            connection.close()
        }
        verify(exactly = 1) { launcher.run(job, any()) }
        verify(exactly = 1) { releaseStatement.executeQuery() }
        verify(exactly = 1) { connection.close() }
        verify(exactly = 1) { dataSource.connection }
    }

    @Test
    fun `작업 시작이 성공했을 때__실행하면__동기 실행이 끝난 뒤에 잠금을 푼다`() {
        // given
        val execution = JobExecution(7L)

        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        every { connection.prepareStatement(RELEASE) } returns releaseStatement
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 1
        justRun { acquireResult.close() }
        justRun { releaseStatement.setInt(any(), any()) }
        justRun { releaseStatement.setQueryTimeout(any()) }
        every { releaseStatement.executeQuery() } returns releaseResult
        justRun { releaseStatement.close() }
        every { releaseResult.next() } returns true
        every { releaseResult.getObject(any<Int>()) } returns 1
        justRun { releaseResult.close() }
        every { planFactory.create(any()) } returns SYNC_PLAN
        every { job.name } returns "apmsAnimalSyncJob"
        every { executionRecovery.recoverOrReject(any()) } returns 0
        every { launcher.run(any(), any()) } returns execution

        // when
        val result = runner.run()

        // then
        assertThat(result).isSameAs(execution)
        verifyOrder {
            acquireStatement.executeQuery()
            executionRecovery.recoverOrReject("apmsAnimalSyncJob")
            launcher.run(
                job,
                match<JobParameters> { parameters ->
                    parameters.getString("requestId") != null &&
                        ApmsSyncPlan.from(parameters).end == LocalDate.of(2026, 9, 10)
                },
            )
            releaseStatement.executeQuery()
            connection.close()
        }
        verify(exactly = 1) { acquireStatement.executeQuery() }
        verify(exactly = 1) { executionRecovery.recoverOrReject("apmsAnimalSyncJob") }
        verify(exactly = 1) { launcher.run(job, any()) }
        verify(exactly = 1) { releaseStatement.executeQuery() }
        verify(exactly = 1) { connection.close() }
        verify(exactly = 0) { connection.abort(any()) }
        verify(exactly = 1) { acquireStatement.setInt(1, 1) }
        verify(exactly = 1) { acquireStatement.setQueryTimeout(5) }
        verify(exactly = 1) { releaseStatement.setInt(1, 1) }
        verify(exactly = 0) { connection.metaData }
        verify(exactly = 1) { dataSource.connection }
    }

    @Test
    fun `잠금을 소유하지 않은 채 해제될 때__실행하면__연결을 폐기하고 성공으로 보고하지 않는다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        every { connection.prepareStatement(RELEASE) } returns releaseStatement
        justRun { connection.abort(any()) }
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 1
        justRun { acquireResult.close() }
        justRun { releaseStatement.setInt(any(), any()) }
        justRun { releaseStatement.setQueryTimeout(any()) }
        every { releaseStatement.executeQuery() } returns releaseResult
        justRun { releaseStatement.close() }
        every { releaseResult.next() } returns true
        every { releaseResult.getObject(any<Int>()) } returns 0
        justRun { releaseResult.close() }
        every { planFactory.create(any()) } returns SYNC_PLAN
        every { job.name } returns "apmsAnimalSyncJob"
        every { executionRecovery.recoverOrReject(any()) } returns 0
        every { launcher.run(any(), any()) } returns JobExecution(7L)

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)
        verify(exactly = 1) { connection.abort(any()) }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `잠금 해제용 연결이 실패할 때__실행하면__연결을 폐기하고 닫힌 상태로 실패한다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        every { connection.prepareStatement(RELEASE) } throws SQLException("test failure")
        justRun { connection.abort(any()) }
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 1
        justRun { acquireResult.close() }
        every { planFactory.create(any()) } returns SYNC_PLAN
        every { job.name } returns "apmsAnimalSyncJob"
        every { executionRecovery.recoverOrReject(any()) } returns 0
        every { launcher.run(any(), any()) } returns JobExecution(7L)

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)
        verify(exactly = 1) { connection.abort(any()) }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `잠금 획득용 연결이 실패할 때__실행하면__작업 시작 없이 연결을 폐기한다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } throws SQLException("test failure")
        justRun { connection.abort(any()) }
        justRun { connection.close() }

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)
        verify(exactly = 1) { connection.abort(any()) }
        verify(exactly = 1) { connection.close() }
        confirmVerified(executionRecovery, launcher)
    }

    @Test
    fun `작업 계획 생성이 실패할 때__실행하면__작업 시작 없이 잠금을 푼다`() {
        // given
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(ACQUIRE) } returns acquireStatement
        every { connection.prepareStatement(RELEASE) } returns releaseStatement
        justRun { connection.close() }
        justRun { acquireStatement.setInt(any(), any()) }
        justRun { acquireStatement.setQueryTimeout(any()) }
        every { acquireStatement.executeQuery() } returns acquireResult
        justRun { acquireStatement.close() }
        every { acquireResult.next() } returns true
        every { acquireResult.getObject(any<Int>()) } returns 1
        justRun { acquireResult.close() }
        justRun { releaseStatement.setInt(any(), any()) }
        justRun { releaseStatement.setQueryTimeout(any()) }
        every { releaseStatement.executeQuery() } returns releaseResult
        justRun { releaseStatement.close() }
        every { releaseResult.next() } returns true
        every { releaseResult.getObject(any<Int>()) } returns 1
        justRun { releaseResult.close() }
        every { planFactory.create(any()) } throws IllegalStateException("history unavailable")
        every { job.name } returns "apmsAnimalSyncJob"
        every { executionRecovery.recoverOrReject(any()) } returns 0

        // when
        val thrown = assertThatThrownBy { runner.run() }

        // then
        thrown.hasMessage("history unavailable")
        confirmVerified(launcher)
        verify(exactly = 1) { planFactory.create("apmsAnimalSyncJob") }
        verify(exactly = 1) { releaseStatement.executeQuery() }
        verify(exactly = 1) { connection.close() }
    }

    companion object {
        private const val ACQUIRE = "SELECT CASE WHEN pg_try_advisory_lock(1346459458,?) THEN 1 ELSE 0 END"
        private const val RELEASE = "SELECT CASE WHEN pg_advisory_unlock(1346459458,?) THEN 1 ELSE 0 END"

        private val DAY: LocalDate = LocalDate.of(2026, 9, 10)
        private val SYNC_PLAN = ApmsSyncPlan(
            DAY.minusDays(30),
            DAY.minusDays(30).withDayOfMonth(1),
            DAY.minusDays(30),
            DAY,
        )
    }
}
