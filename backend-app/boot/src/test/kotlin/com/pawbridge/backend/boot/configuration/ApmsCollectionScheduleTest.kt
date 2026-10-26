package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.core.task.TaskRejectedException
import org.springframework.mock.env.MockEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApmsCollectionScheduleTest {
    @Test
    fun `시작한 적이 없을 때__중지하면__러너를 호출하지 않는다`() {
        // given
        val runner = mockk<ApmsBatchRunner>()
        val schedule = ApmsCollectionSchedule(runner, "0 */30 * * * *")

        // when
        schedule.stop()

        // then
        assertThat(schedule.isRunning).isFalse()
        confirmVerified(runner)
    }

    @Test
    fun `예약된 러너가 실행 중일 때__중지하면__인터럽트나 새 틱 없이 러너가 돌아오길 기다린다`() {
        // given
        val runner = mockk<ApmsBatchRunner>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val execution = JobExecution(1L)
        execution.status = BatchStatus.COMPLETED
        val schedule = ApmsCollectionSchedule(runner, "* * * * * *")
        val stopping = Thread {
            stopStarted.countDown()
            schedule.stop()
            stopped.countDown()
        }

        every { runner.run() } answers {
            entered.countDown()
            release.await()
            assertThat(Thread.currentThread().isInterrupted).isFalse()
            execution
        }

        try {
            // when
            schedule.start()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            stopping.start()
            assertThat(stopStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse()
            release.countDown()

            // then
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(schedule.isRunning).isFalse()
            verify(exactly = 1) { runner.run() }
        } finally {
            release.countDown()
            schedule.stop()
            stopping.join(5000)
        }
    }

    @Test
    fun `작업자가 하나이고 제한 큐가 가득 찼을 때__작업이 더 오면__호출자 스레드 실행 없이 거절한다`() {
        // given
        val environment = MockEnvironment()
            .withProperty("apms.api.base-url", "https://provider.example.test")
            .withProperty("apms.api.service-key", "fixture-key")
        val executor = AnimalApmsCollectionConfiguration().apmsBatchTaskExecutor(ApmsScheduleSettings(environment))
        executor.initialize()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejectedRan = CountDownLatch(1)

        // when, then
        try {
            executor.execute {
                entered.countDown()
                try {
                    release.await()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            for (i in 0 until 4) {
                executor.execute { }
            }
            assertThatThrownBy { executor.execute { rejectedRan.countDown() } }
                .isInstanceOf(TaskRejectedException::class.java)
            assertThat(rejectedRan.count).isEqualTo(1)
            assertThat(executor.poolSize).isEqualTo(1)
        } finally {
            release.countDown()
            executor.destroy()
        }
    }
}
