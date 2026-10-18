package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.persistence.CollectionSessionLock;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.task.SyncTaskExecutor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: a newly created, owner-marked local DB; never points at an application DB. */
@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_APMS_TEST_OWNER", matches = "[0-9a-f]{32}")
class ApmsCollectionSchedulePostgresqlTest {
    public static void main(String[] args) throws Exception {
        String owner = System.getenv("PAWBRIDGE_APMS_TEST_OWNER");
        if (owner == null || !owner.matches("[0-9a-f]{32}")) throw new IllegalStateException("Missing isolated DB owner");
        new ApmsCollectionSchedulePostgresqlTest().scheduledAndManualExecutionShareRealPgLockAndDrainOnStop();
        System.out.println("ISOLATED_PG_SCHEDULER_PASS");
    }

    @Test
    void scheduledAndManualExecutionShareRealPgLockAndDrainOnStop() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL("jdbc:postgresql://127.0.0.1:35439/pawbridge_scheduler_test?connectTimeout=5&socketTimeout=10");
        source.setUser("schedule_test");
        source.setPassword(System.getenv("PAWBRIDGE_APMS_TEST_PASSWORD"));
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=current_database()")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("pawbridge-apms-scheduler-test:" + System.getenv("PAWBRIDGE_APMS_TEST_OWNER"));
        }

        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var launches = new AtomicInteger();
        var repository = new ResourcelessJobRepository();
        var step = new StepBuilder("syntheticCollection", repository).tasklet((contribution, context) -> {
            if (launches.incrementAndGet() == 1) {
                started.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return RepeatStatus.FINISHED;
        }, new ResourcelessTransactionManager()).build();
        var job = new JobBuilder("apmsAnimalSyncJob", repository).start(step).build();
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository); launcher.setTaskExecutor(new SyncTaskExecutor()); launcher.afterPropertiesSet();
        var planFactory = mock(ApmsSyncPlanFactory.class);
        var day = LocalDate.of(2026, 10, 16);
        when(planFactory.create(job.getName())).thenReturn(new ApmsSyncPlan(day, day, day, day));
        var runner = new ApmsBatchRunner(source, mock(ApmsBatchExecutionRecovery.class), launcher, job, planFactory);
        var stopper = Executors.newSingleThreadExecutor();
        try {
            new WebApplicationContextRunner().withUserConfiguration(ApmsCollectionSchedule.class)
                    .withBean(ApmsBatchRunner.class, () -> runner)
                    .withPropertyValues("apms.batch.schedule-enabled=true", "apms.batch.collection-cron=*/1 * * * * *")
                    .run(context -> {
                        try {
                            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                            assertThatThrownBy(runner::run).isInstanceOf(ApmsBatchRunner.AlreadyRunningException.class);
                            assertThat(launches.get()).isEqualTo(1);
                            var stopEntered = new CountDownLatch(1);
                            var stopped = stopper.submit(() -> {
                                stopEntered.countDown();
                                context.getBean(ApmsCollectionSchedule.class).stop();
                            });
                            assertThat(stopEntered.await(1, TimeUnit.SECONDS)).isTrue();
                            assertThatThrownBy(() -> stopped.get(150, TimeUnit.MILLISECONDS))
                                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                            release.countDown();
                            stopped.get(3, TimeUnit.SECONDS);
                            assertThat(runner.run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
                            assertThat(launches.get()).isEqualTo(2);
                            try (var connection = source.getConnection()) {
                                assertThat(CollectionSessionLock.query(connection, "pawbridge_animal.apmsAnimalSyncJob", CollectionSessionLock.Operation.ACQUIRE)).isEqualTo(1);
                                assertThat(CollectionSessionLock.query(connection, "pawbridge_animal.apmsAnimalSyncJob", CollectionSessionLock.Operation.RELEASE)).isEqualTo(1);
                            }
                        } finally { release.countDown(); }
                    });
        } finally { release.countDown(); stopper.shutdownNow(); }
    }
}
