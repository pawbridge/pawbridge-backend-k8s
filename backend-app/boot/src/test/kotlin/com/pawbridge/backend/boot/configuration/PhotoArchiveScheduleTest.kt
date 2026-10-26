package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PhotoArchiveScheduleTest {
    @Test
    fun `한 번도 시작하지 않았을 때__중지하면__보관 실행을 시작하지 않는다`() {
        // given
        val worker = mockk<PhotoArchiveWorker>()
        val schedule = PhotoArchiveSchedule(worker, PhotoArchiveProperties(), 1)

        justRun { worker.requestStop() }

        // when
        schedule.stop()

        // then
        verify(exactly = 1) { worker.requestStop() }
        verify(exactly = 0) { worker.scheduled() }
    }

    @Test
    fun `진행 중인 보관이 있을 때__중지하면__인터럽트나 재실행 없이 반환을 기다린다`() {
        // given
        val worker = mockk<PhotoArchiveWorker>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val properties = PhotoArchiveProperties()
        properties.initialDelayMs = 0
        properties.intervalMs = 1000
        val schedule = PhotoArchiveSchedule(worker, properties, 3)
        val stopping = Thread {
            schedule.stop()
            stopped.countDown()
        }

        justRun { worker.resume() }
        every { worker.requestStop() } answers {
            requested.countDown()
        }
        every { worker.scheduled() } answers {
            entered.countDown()
            release.await()
            assertThat(Thread.currentThread().isInterrupted).isFalse()
        }

        try {
            // when
            schedule.start()
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()
            stopping.start()
            assertThat(requested.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse()
            release.countDown()

            // then
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(schedule.isRunning).isFalse()
            verify(exactly = 1) { worker.scheduled() }
        } finally {
            release.countDown()
            schedule.stop()
            stopping.join(3000)
        }
    }

    @Test
    fun `종료 대기 시간이 잘못됐을 때__생성하면__무제한이거나 0 이하인 대기를 거절한다`() {
        // given
        val worker = mockk<PhotoArchiveWorker>()

        // when
        val zeroSeconds = { PhotoArchiveSchedule(worker, PhotoArchiveProperties(), 0) }
        val beyondLimit = { PhotoArchiveSchedule(worker, PhotoArchiveProperties(), 301) }

        // then
        assertThatThrownBy { zeroSeconds() }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { beyondLimit() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `작업이 종료 대기 시간을 넘길 때__중지하면__종료를 단정하지 않고 경고를 남긴다`() {
        // given
        val worker = mockk<PhotoArchiveWorker>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerThread = AtomicReference<Thread>()
        val properties = PhotoArchiveProperties()
        properties.initialDelayMs = 0
        properties.intervalMs = 1000
        val schedule = PhotoArchiveSchedule(worker, properties, 1)
        val logger = LoggerFactory.getLogger(PhotoArchiveSchedule::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        justRun { worker.resume() }
        justRun { worker.requestStop() }
        every { worker.scheduled() } answers {
            workerThread.set(Thread.currentThread())
            entered.countDown()
            while (release.count != 0L) {
                try {
                    release.await()
                } catch (ignored: InterruptedException) {
                    // Model I/O that has not returned yet.
                }
            }
        }

        try {
            // when
            schedule.start()
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()
            schedule.stop()

            // then
            assertThat(schedule.isRunning).isFalse()
            assertThat(workerThread.get().isAlive).isTrue()
            assertThat(workerThread.get().isDaemon).isTrue()
            assertThat(appender.list).anySatisfy { event ->
                assertThat(event.formattedMessage)
                    .isEqualTo("APMS photo archive shutdown incomplete; lease recovery may be required")
            }
            verify(exactly = 1) { worker.scheduled() }
            release.countDown()
            workerThread.get().join(3000)
            assertThat(workerThread.get().isAlive).isFalse()
        } finally {
            release.countDown()
            schedule.stop()
            workerThread.get()?.join(3000)
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
