package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import org.slf4j.LoggerFactory
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PhotoArchiveScheduleTest {
    @Test
    fun givenNeverStarted__whenStopped__thenNoArchiveRunStarts() {
        val worker = mock(PhotoArchiveWorker::class.java)
        PhotoArchiveSchedule(worker, PhotoArchiveProperties(), 1).stop()
        verify(worker).requestStop()
        verify(worker, never()).scheduled()
    }

    @Test
    fun givenInFlightArchive__whenStopped__thenWaitForReturnWithoutInterruptOrSecondRun() {
        val worker = mock(PhotoArchiveWorker::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        doAnswer(
            Answer<Any?> {
                requested.countDown()
                null
            },
        ).`when`(worker).requestStop()
        doAnswer(
            Answer<Any?> {
                entered.countDown()
                release.await()
                assertThat(Thread.currentThread().isInterrupted).isFalse()
                null
            },
        ).`when`(worker).scheduled()
        val properties = PhotoArchiveProperties()
        properties.initialDelayMs = 0
        properties.intervalMs = 1000
        val schedule = PhotoArchiveSchedule(worker, properties, 3)
        val stopping = Thread {
            schedule.stop()
            stopped.countDown()
        }
        try {
            schedule.start()
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()
            stopping.start()
            assertThat(requested.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse()
            release.countDown()
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(schedule.isRunning).isFalse()
            verify(worker, times(1)).scheduled()
        } finally {
            release.countDown()
            schedule.stop()
            stopping.join(3000)
        }
    }

    @Test
    fun givenInvalidShutdownBudget__whenConstructed__thenRejectUnboundedOrNonpositiveWait() {
        val worker = mock(PhotoArchiveWorker::class.java)
        assertThatThrownBy { PhotoArchiveSchedule(worker, PhotoArchiveProperties(), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PhotoArchiveSchedule(worker, PhotoArchiveProperties(), 301) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun givenWorkerBeyondShutdownBudget__whenStopped__thenWarnWithoutClaimingWorkerTermination() {
        val worker = mock(PhotoArchiveWorker::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerThread = AtomicReference<Thread>()
        doAnswer(
            Answer<Any?> {
                workerThread.set(Thread.currentThread())
                entered.countDown()
                while (release.count != 0L) {
                    try {
                        release.await()
                    } catch (ignored: InterruptedException) {
                        // Model I/O that has not returned yet.
                    }
                }
                null
            },
        ).`when`(worker).scheduled()
        val properties = PhotoArchiveProperties()
        properties.initialDelayMs = 0
        properties.intervalMs = 1000
        val schedule = PhotoArchiveSchedule(worker, properties, 1)
        val logger = LoggerFactory.getLogger(PhotoArchiveSchedule::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            schedule.start()
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()
            schedule.stop()
            assertThat(schedule.isRunning).isFalse()
            val executor = ReflectionTestUtils.getField(schedule, "executor") as ScheduledThreadPoolExecutor
            assertThat(executor.isTerminated).isFalse()
            assertThat(workerThread.get().isDaemon).isTrue()
            assertThat(appender.list).anySatisfy { event ->
                assertThat(event.formattedMessage)
                    .isEqualTo("APMS photo archive shutdown incomplete; lease recovery may be required")
            }
            verify(worker, times(1)).scheduled()
            release.countDown()
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue()
        } finally {
            release.countDown()
            schedule.stop()
            val executor = ReflectionTestUtils.getField(schedule, "executor") as ScheduledThreadPoolExecutor?
            executor?.awaitTermination(3, TimeUnit.SECONDS)
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
