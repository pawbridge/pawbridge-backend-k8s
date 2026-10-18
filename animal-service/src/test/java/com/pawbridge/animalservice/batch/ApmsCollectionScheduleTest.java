package com.pawbridge.animalservice.batch;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ApmsCollectionScheduleTest {
    private final ApmsBatchRunner runner = mock(ApmsBatchRunner.class);
    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(ApmsCollectionSchedule.class)
            .withBean(ApmsBatchRunner.class, () -> runner);

    @Test
    void absentOrDisabledFlagDoesNotScheduleCollection() {
        web.run(context -> assertThat(context).doesNotHaveBean(ApmsCollectionSchedule.class));
        web.withPropertyValues("apms.batch.schedule-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApmsCollectionSchedule.class));
        verifyNoInteractions(runner);
    }

    @Test
    void nonWebMigrationProcessDoesNotScheduleEvenIfEnabled() {
        new ApplicationContextRunner().withUserConfiguration(ApmsCollectionSchedule.class)
                .withBean(ApmsBatchRunner.class, () -> runner)
                .withPropertyValues("apms.batch.schedule-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ApmsCollectionSchedule.class));
        verifyNoInteractions(runner);
    }

    @Test
    void enabledWebProcessRegistersHalfHourlyKoreanSchedule() {
        web.withPropertyValues("apms.batch.schedule-enabled=true").run(context -> {
            var schedule = context.getBean(ApmsCollectionSchedule.class);
            assertThat(schedule.isRunning()).isTrue();
            var scheduler = (org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler)
                    org.springframework.test.util.ReflectionTestUtils.getField(schedule, "scheduler");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
            var trigger = (org.springframework.scheduling.support.CronTrigger)
                    org.springframework.test.util.ReflectionTestUtils.getField(schedule, "trigger");
            var instant = java.time.Instant.parse("2026-10-16T00:12:00Z");
            var triggerContext = new org.springframework.scheduling.support.SimpleTriggerContext(
                    java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC));
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(java.time.Instant.parse("2026-10-16T00:30:00Z"));
            assertThat(trigger.getExpression()).isEqualTo("0 */30 * * * *");
            schedule.start();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
        });
    }

    @Test
    void invalidCronFailsStartup() {
        web.withPropertyValues("apms.batch.schedule-enabled=true", "apms.batch.collection-cron=invalid")
                .run(context -> assertThat(context).hasFailed());
        verifyNoInteractions(runner);
    }

    @Test
    void scheduledTicksRecoverAfterFailureWithoutBlockingSharedTasks() throws Exception {
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var recovered = new CountDownLatch(1);
        when(runner.run()).thenAnswer(call -> {
            first.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            throw new ApmsBatchRunner.UnavailableException();
        }).thenAnswer(call -> { recovered.countDown(); return completed(); });
        web.withUserConfiguration(SharedScheduling.class)
                .withPropertyValues("apms.batch.schedule-enabled=true", "apms.batch.collection-cron=*/1 * * * * *")
                .run(context -> {
                    try {
                        assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
                        var heartbeat = context.getBean(Heartbeat.class);
                        heartbeat.afterCollectionStarted = new CountDownLatch(2);
                        assertThat(heartbeat.afterCollectionStarted.await(2, TimeUnit.SECONDS)).isTrue();
                        verify(runner, times(1)).run();
                        release.countDown();
                        assertThat(recovered.await(3, TimeUnit.SECONDS)).isTrue();
                        var schedule = context.getBean(ApmsCollectionSchedule.class);
                        schedule.stop();
                        assertThat(schedule.isRunning()).isFalse();
                        int calls = mockingDetails(runner).getInvocations().size();
                        Thread.sleep(1100);
                        assertThat(mockingDetails(runner).getInvocations()).hasSize(calls);
                    } finally { release.countDown(); }
                });
    }

    @Test
    void completedJobIsReported(CapturedOutput output) throws Exception {
        when(runner.run()).thenReturn(completed());
        schedule().collect();
        verify(runner).run();
        assertThat(output).contains("APMS scheduled collection completed: executionId=7");
    }

    @Test
    void failedBatchReturnIsNotReportedAsSuccess(CapturedOutput output) throws Exception {
        var execution = completed(); execution.setStatus(BatchStatus.FAILED);
        when(runner.run()).thenReturn(execution);
        schedule().collect();
        assertThat(output).contains("status=FAILED").doesNotContain("collection completed:");
    }

    @Test
    void skippedItemsAreNotReportedAsSuccess(CapturedOutput output) throws Exception {
        var execution = completed();
        var step = new StepExecution("collect", execution); step.setReadSkipCount(1); execution.addStepExecutions(java.util.List.of(step));
        when(runner.run()).thenReturn(execution);
        schedule().collect();
        assertThat(output).contains("skipCount=1").doesNotContain("collection completed:");
    }

    @Test
    void retryableCountMismatchRemainsIncompleteRatherThanFullySuccessful(CapturedOutput output) throws Exception {
        var execution = completed(); execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 1);
        when(runner.run()).thenReturn(execution);
        schedule().collect();
        assertThat(output).contains("collection incomplete:").doesNotContain("collection completed:", "collection failed:");
    }

    @Test
    void competingExecutionIsSkippedWithoutImmediateRetry(CapturedOutput output) throws Exception {
        when(runner.run()).thenThrow(new ApmsBatchRunner.AlreadyRunningException());
        schedule().collect();
        verify(runner).run();
        assertThat(output).contains("collection skipped:").doesNotContain("collection completed:");
    }

    @Test
    void exceptionIsReportedWithoutLeakingUpstreamCredentials(CapturedOutput output) throws Exception {
        when(runner.run()).thenThrow(new IllegalStateException("serviceKey=synthetic-secret"));
        schedule().collect();
        assertThat(output).contains("exceptionType=IllegalStateException").doesNotContain("synthetic-secret");
    }

    private ApmsCollectionSchedule schedule() { return new ApmsCollectionSchedule(runner, "0 */30 * * * *"); }
    private JobExecution completed() { var execution = new JobExecution(7L); execution.setStatus(BatchStatus.COMPLETED); return execution; }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SharedScheduling {
        @Bean Heartbeat heartbeat() { return new Heartbeat(); }
    }

    static class Heartbeat {
        volatile CountDownLatch afterCollectionStarted = new CountDownLatch(0);
        @Scheduled(fixedDelay = 25) public void tick() { afterCollectionStarted.countDown(); }
    }
}
