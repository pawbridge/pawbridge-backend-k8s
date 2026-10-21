package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.env.MockEnvironment;

class ApmsCollectionScheduleTest {
    @Test void givenNeverStarted__whenStopped__thenNoRunnerCall() {
        var runner = mock(ApmsBatchRunner.class);
        var schedule = new ApmsCollectionSchedule(runner, "0 */30 * * * *");
        schedule.stop();
        assertThat(schedule.isRunning()).isFalse();
        verifyNoInteractions(runner);
    }

    @Test void givenActiveScheduledRunner__whenStopped__thenWaitForRunnerReturnWithoutInterruptOrNewTick() throws Exception {
        var runner = mock(ApmsBatchRunner.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopStarted = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var execution = new JobExecution(1L);
        execution.setStatus(BatchStatus.COMPLETED);
        when(runner.run()).thenAnswer(ignored -> {
            entered.countDown();
            release.await();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return execution;
        });
        var schedule = new ApmsCollectionSchedule(runner, "* * * * * *");
        var stopping = new Thread(() -> {
            stopStarted.countDown();
            schedule.stop();
            stopped.countDown();
        });
        try {
            schedule.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            stopping.start();
            assertThat(stopStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(schedule.isRunning()).isFalse();
            verify(runner, times(1)).run();
        } finally {
            release.countDown();
            schedule.stop();
            stopping.join(5000);
        }
    }

    @Test void givenOneWorkerAndFullBoundedQueue__whenAnotherTaskArrives__thenRejectWithoutCallerThreadExecution() throws Exception {
        var environment = new MockEnvironment().withProperty("apms.api.base-url", "https://provider.example.test")
                .withProperty("apms.api.service-key", "fixture-key");
        var executor = new AnimalApmsCollectionConfiguration().apmsBatchTaskExecutor(new ApmsScheduleSettings(environment));
        executor.initialize();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var rejectedRan = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 4; i++) executor.execute(() -> {});
            assertThatThrownBy(() -> executor.execute(rejectedRan::countDown)).isInstanceOf(TaskRejectedException.class);
            assertThat(rejectedRan.getCount()).isEqualTo(1);
            assertThat(executor.getPoolSize()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.destroy();
        }
    }
}
