package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration;
import com.pawbridge.backend.community.internal.note.application.NoteExpiry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ErrorHandler;

@ExtendWith(OutputCaptureExtension.class)
class NoteExpiryConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void givenDisabledOrAbsentCleanupFlag__whenAssembling__thenRegisterNoExpiryOrSchedulingBeans(String flag) {
        var runner = new ApplicationContextRunner().withUserConfiguration(NoteExpiryConfiguration.class);
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=" + flag);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(NoteExpiry.class)).isEmpty();
            assertThat(context.getBeansOfType(NoteExpiryConfiguration.NoteExpiryJob.class)).isEmpty();
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
    }

    @Test
    void givenEnabledCleanupAndExistingWriterInputs__whenAssembling__thenUseDedicatedTransactionAndBoundedQualifiedScheduler() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(JdbcTransactionManager.class);
        var clock = mock(Clock.class);
        enabled(jdbc, manager, clock).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(NoteExpiry.class)).hasSize(1);
            var expiry = context.getBean(NoteExpiry.class);
            assertThat(ReflectionTestUtils.getField(expiry, "jdbc")).isSameAs(jdbc);
            assertThat(ReflectionTestUtils.getField(expiry, "clock")).isSameAs(clock);
            var transaction = (TransactionTemplate) ReflectionTestUtils.getField(expiry, "transaction");
            assertThat(transaction.getTransactionManager()).isSameAs(manager);
            assertThat(transaction.getTimeout()).isEqualTo(15);
            assertThat(transaction.isReadOnly()).isFalse();
            var job = context.getBean(NoteExpiryConfiguration.NoteExpiryJob.class);
            assertThat(ReflectionTestUtils.getField(job, "expiry")).isSameAs(expiry);

            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
            var scheduler = context.getBean("noteExpiryScheduler", ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getClass()).isEqualTo(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            assertThat(ReflectionTestUtils.getField(scheduler, "acceptTasksAfterContextClose")).isEqualTo(false);
            assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(false);
            assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).isEqualTo(5000L);
            assertThat(context.getBeanFactory().getDependenciesForBean("noteExpiryScheduler")).contains("writeDataSource");

            var processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(processor.getScheduledTasks()).hasSize(1);
            var task = processor.getScheduledTasks().iterator().next().getTask();
            assertThat(task).isInstanceOf(FixedDelayTask.class);
            var fixedDelay = (FixedDelayTask) task;
            assertThat(fixedDelay.getInitialDelayDuration()).isEqualTo(Duration.ofMillis(300000));
            assertThat(fixedDelay.getIntervalDuration()).isEqualTo(Duration.ofMillis(300000));
            var metadata = NoteExpiryConfiguration.NoteExpiryJob.class.getMethod("expire").getAnnotation(Scheduled.class);
            assertThat(metadata.scheduler()).isEqualTo("noteExpiryScheduler");
            assertThat(metadata.initialDelay()).isEqualTo(300000);
            assertThat(metadata.fixedDelay()).isEqualTo(300000);
            verifyNoInteractions(jdbc, manager, clock);
        });
    }

    @Test
    void givenPrivateFailureDetails__whenSchedulerHandlesFailure__thenLogOnlyTheFailureType(CapturedOutput output) {
        enabled(mock(JdbcTemplate.class), mock(JdbcTransactionManager.class), mock(Clock.class)).run(context -> {
            assertThat(context).hasNotFailed();
            var scheduler = context.getBean("noteExpiryScheduler", ThreadPoolTaskScheduler.class);
            var handler = (ErrorHandler) ReflectionTestUtils.getField(scheduler, "errorHandler");
            handler.handleError(new IllegalStateException("private-note-body private-token DELETE FROM private_notes",
                    new RuntimeException("private-cause")));
            assertThat(output.getAll()).contains("Private note expiry failed (IllegalStateException)")
                    .doesNotContain("private-note-body", "private-token", "DELETE FROM", "private-cause", "IllegalStateException:");
        });
    }

    private ApplicationContextRunner enabled(JdbcTemplate jdbc, JdbcTransactionManager manager, Clock clock) {
        return new ApplicationContextRunner()
                .withUserConfiguration(NoteExpiryConfiguration.class)
                .withPropertyValues("pawbridge.jobs.cleanup-enabled=true")
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("writeDataSource", new Object());
                    context.getBeanFactory().registerSingleton("writeJdbc", jdbc);
                    context.getBeanFactory().registerSingleton("writeTransactionManager", manager);
                    context.getBeanFactory().registerSingleton("readClock", clock);
                });
    }
}
