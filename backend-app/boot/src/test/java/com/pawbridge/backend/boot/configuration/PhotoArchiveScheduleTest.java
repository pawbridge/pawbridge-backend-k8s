package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PhotoArchiveScheduleTest {
    @Test void givenNeverStarted__whenStopped__thenNoArchiveRunStarts() {
        var worker = mock(PhotoArchiveWorker.class);
        new PhotoArchiveSchedule(worker, new PhotoArchiveProperties(), 1).stop();
        verify(worker).requestStop();
        verify(worker, never()).scheduled();
    }

    @Test void givenInFlightArchive__whenStopped__thenWaitForReturnWithoutInterruptOrSecondRun() throws Exception {
        var worker = mock(PhotoArchiveWorker.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requested = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        doAnswer(call -> { requested.countDown(); return null; }).when(worker).requestStop();
        doAnswer(call -> {
            entered.countDown();
            release.await();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(worker).scheduled();
        var properties = new PhotoArchiveProperties();
        properties.setInitialDelayMs(0); properties.setIntervalMs(1000);
        var schedule = new PhotoArchiveSchedule(worker, properties, 3);
        var stopping = new Thread(() -> { schedule.stop(); stopped.countDown(); });
        try {
            schedule.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            stopping.start();
            assertThat(requested.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(schedule.isRunning()).isFalse();
            verify(worker, times(1)).scheduled();
        } finally {
            release.countDown(); schedule.stop(); stopping.join(3000);
        }
    }

    @Test void givenInvalidShutdownBudget__whenConstructed__thenRejectUnboundedOrNonpositiveWait() {
        var worker = mock(PhotoArchiveWorker.class);
        assertThatThrownBy(() -> new PhotoArchiveSchedule(worker, new PhotoArchiveProperties(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhotoArchiveSchedule(worker, new PhotoArchiveProperties(), 301))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void givenWorkerBeyondShutdownBudget__whenStopped__thenWarnWithoutClaimingWorkerTermination() throws Exception {
        var worker = mock(PhotoArchiveWorker.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(call -> {
            workerThread.set(Thread.currentThread());
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Model I/O that has not returned yet. */ }
            }
            return null;
        }).when(worker).scheduled();
        var properties = new PhotoArchiveProperties();
        properties.setInitialDelayMs(0); properties.setIntervalMs(1000);
        var schedule = new PhotoArchiveSchedule(worker, properties, 1);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PhotoArchiveSchedule.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            schedule.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            schedule.stop();
            assertThat(schedule.isRunning()).isFalse();
            var executor = (java.util.concurrent.ScheduledThreadPoolExecutor)
                    org.springframework.test.util.ReflectionTestUtils.getField(schedule, "executor");
            assertThat(executor.isTerminated()).isFalse();
            assertThat(workerThread.get().isDaemon()).isTrue();
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .isEqualTo("APMS photo archive shutdown incomplete; lease recovery may be required"));
            verify(worker, times(1)).scheduled();
            release.countDown();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            schedule.stop();
            var executor = (java.util.concurrent.ScheduledThreadPoolExecutor)
                    org.springframework.test.util.ReflectionTestUtils.getField(schedule, "executor");
            if (executor != null) executor.awaitTermination(3, TimeUnit.SECONDS);
            logger.detachAppender(appender); appender.stop();
        }
    }
}
