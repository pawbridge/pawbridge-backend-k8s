package com.pawbridge.animalservice.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApmsBatchRunnerTest {
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement acquireStatement;
    @Mock private PreparedStatement releaseStatement;
    @Mock private ResultSet acquireResult;
    @Mock private ResultSet releaseResult;
    @Mock private JobExplorer explorer;
    @Mock private JobLauncher launcher;
    @Mock private Job job;
    @Mock private ApmsSyncPlanFactory planFactory;
    private ApmsBatchRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        runner = new ApmsBatchRunner(dataSource, explorer, launcher, job, planFactory);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @Test
    void givenLockOwnedElsewhere__whenRun__thenRejectWithoutLaunchingOrReleasing() throws Exception {
        acquired(0);
        assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.AlreadyRunningException.class);
        verifyNoInteractions(explorer, launcher);
        verify(connection, never()).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(connection).close();
    }

    @Test
    void givenIndeterminateLock__whenRun__thenAbortWithoutLaunching() throws Exception {
        acquired(null);
        assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.UnavailableException.class);
        verify(connection).abort(any());
        verify(connection).close();
        verifyNoInteractions(explorer, launcher);
    }

    @Test
    void givenRunningMetadata__whenRun__thenReleaseAndRequireRecoveryCheck() throws Exception {
        acquired(1);
        released(1);
        when(job.getName()).thenReturn("apmsAnimalSyncJob");
        when(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(Set.of(new JobExecution(1L)));
        assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.UnavailableException.class);
        verifyNoInteractions(launcher);
        verify(releaseStatement).executeQuery();
        verify(connection).close();
    }

    @Test
    void givenLaunchFailure__whenRun__thenReleaseSameConnection() throws Exception {
        allowedStart();
        released(1);
        when(launcher.run(eq(job), any())).thenThrow(new JobParametersInvalidException("invalid request"));
        assertThatThrownBy(runner::run).isInstanceOf(JobParametersInvalidException.class);
        var order = inOrder(launcher, releaseStatement, connection);
        order.verify(launcher).run(eq(job), any());
        order.verify(releaseStatement).executeQuery();
        order.verify(connection).close();
        verify(dataSource).getConnection();
    }

    @Test
    void givenSuccessfulLaunch__whenRun__thenReleaseAfterSynchronousCompletion() throws Exception {
        allowedStart();
        released(1);
        var execution = new JobExecution(7L);
        when(launcher.run(eq(job), any())).thenReturn(execution);
        assertThat(runner.run()).isSameAs(execution);
        var order = inOrder(acquireStatement, explorer, launcher, releaseStatement, connection);
        order.verify(acquireStatement).executeQuery();
        order.verify(explorer).findRunningJobExecutions("apmsAnimalSyncJob");
        order.verify(launcher).run(eq(job), argThat(parameters -> parameters.getString("requestId") != null
                && ApmsSyncPlan.from(parameters).end().equals(java.time.LocalDate.of(2026, 9, 10))));
        order.verify(releaseStatement).executeQuery();
        order.verify(connection).close();
        verify(connection, never()).abort(any());
        verify(dataSource).getConnection();
    }

    @Test
    void givenReleaseNotOwned__whenRun__thenAbortAndNeverReportSuccess() throws Exception {
        allowedStart();
        released(0);
        when(launcher.run(eq(job), any())).thenReturn(new JobExecution(7L));
        assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.UnavailableException.class);
        verify(connection).abort(any());
        verify(connection).close();
    }

    @Test
    void givenReleaseConnectionFailure__whenRun__thenAbortAndFailClosed() throws Exception {
        allowedStart();
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenThrow(new SQLException("test failure"));
        when(launcher.run(eq(job), any())).thenReturn(new JobExecution(7L));
        assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.UnavailableException.class);
        verify(connection).abort(any());
        verify(connection).close();
    }

    @Test
    void givenAcquisitionConnectionFailure__whenRun__thenAbortWithoutLaunching() throws Exception {
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenThrow(new SQLException("test failure"));
        assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.UnavailableException.class);
        verify(connection).abort(any());
        verify(connection).close();
        verifyNoInteractions(explorer, launcher);
    }

    @Test
    void givenPlanningFailure__whenRun__thenReleaseLockWithoutLaunching() throws Exception {
        allowedStart();
        released(1);
        when(planFactory.create("apmsAnimalSyncJob")).thenThrow(new IllegalStateException("history unavailable"));
        assertThatThrownBy(runner::run).hasMessage("history unavailable");
        verifyNoInteractions(launcher);
        verify(releaseStatement).executeQuery();
        verify(connection).close();
    }

    private void allowedStart() throws Exception {
        acquired(1);
        var day = java.time.LocalDate.of(2026, 9, 10);
        when(planFactory.create("apmsAnimalSyncJob")).thenReturn(new ApmsSyncPlan(day.minusDays(30), day.minusDays(30).withDayOfMonth(1), day.minusDays(30), day));
        when(job.getName()).thenReturn("apmsAnimalSyncJob");
        when(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(Set.of());
    }

    private void acquired(Integer value) throws Exception {
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(acquireStatement);
        when(acquireStatement.executeQuery()).thenReturn(acquireResult);
        when(acquireResult.next()).thenReturn(true);
        when(acquireResult.getObject(1)).thenReturn(value);
    }

    private void released(Integer value) throws Exception {
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(releaseStatement);
        when(releaseStatement.executeQuery()).thenReturn(releaseResult);
        when(releaseResult.next()).thenReturn(true);
        when(releaseResult.getObject(1)).thenReturn(value);
    }
}
