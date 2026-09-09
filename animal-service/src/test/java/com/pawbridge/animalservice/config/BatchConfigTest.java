package com.pawbridge.animalservice.config;

import com.pawbridge.animalservice.batch.ApmsBatchRunner;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BatchConfigTest {
    @Test
    void givenDefaultLauncherAlsoPresent__whenApmsRuns__thenUseQualifiedSynchronousLauncher() throws Exception {
        var repository = new ResourcelessJobRepository();
        var explorer = mock(JobExplorer.class);
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        var defaultLauncher = mock(JobLauncher.class);
        var executionThread = new AtomicReference<Thread>();
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getObject(1)).thenReturn(1);
        when(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(Set.of());
        var step = new StepBuilder("work", repository).tasklet((contribution, context) -> {
            executionThread.set(Thread.currentThread());
            return RepeatStatus.FINISHED;
        }, new ResourcelessTransactionManager()).build();
        Job job = new JobBuilder("apmsAnimalSyncJob", repository).start(step).build();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JobRepository.class, () -> repository);
            context.registerBean(JobExplorer.class, () -> explorer);
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(Job.class, () -> job);
            context.registerBean("jobLauncher", JobLauncher.class, () -> defaultLauncher);
            context.register(BatchConfig.class, ApmsBatchRunner.class);
            context.refresh();
            var execution = context.getBean(ApmsBatchRunner.class).run();
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(executionThread.get()).isSameAs(Thread.currentThread());
            verifyNoInteractions(defaultLauncher);
            verify(connection).prepareStatement("SELECT GET_LOCK(?, 0)");
            verify(connection).prepareStatement("SELECT RELEASE_LOCK(?)");
            verify(connection).close();
        }
    }
}
