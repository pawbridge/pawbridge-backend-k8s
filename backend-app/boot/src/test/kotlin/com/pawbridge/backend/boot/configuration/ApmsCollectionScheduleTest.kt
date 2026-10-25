package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.stubbing.Answer
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.core.task.TaskRejectedException
import org.springframework.mock.env.MockEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApmsCollectionScheduleTest {
    @Test
    fun givenNeverStarted__whenStopped__thenNoRunnerCall() {
        val runner = mock(ApmsBatchRunner::class.java)
        val schedule = ApmsCollectionSchedule(runner, "0 */30 * * * *")
        schedule.stop()
        assertThat(schedule.isRunning).isFalse()
        verifyNoInteractions(runner)
    }

    @Test
    fun givenActiveScheduledRunner__whenStopped__thenWaitForRunnerReturnWithoutInterruptOrNewTick() {
        val runner = mock(ApmsBatchRunner::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val execution = JobExecution(1L)
        execution.status = BatchStatus.COMPLETED
        whenever(runner.run()).thenAnswer(
            Answer<JobExecution> {
                entered.countDown()
                release.await()
                assertThat(Thread.currentThread().isInterrupted).isFalse()
                execution
            },
        )
        val schedule = ApmsCollectionSchedule(runner, "* * * * * *")
        val stopping = Thread {
            stopStarted.countDown()
            schedule.stop()
            stopped.countDown()
        }
        try {
            schedule.start()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            stopping.start()
            assertThat(stopStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse()
            release.countDown()
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(schedule.isRunning).isFalse()
            verify(runner, times(1)).run()
        } finally {
            release.countDown()
            schedule.stop()
            stopping.join(5000)
        }
    }

    @Test
    fun givenOneWorkerAndFullBoundedQueue__whenAnotherTaskArrives__thenRejectWithoutCallerThreadExecution() {
        val environment = MockEnvironment()
            .withProperty("apms.api.base-url", "https://provider.example.test")
            .withProperty("apms.api.service-key", "fixture-key")
        val executor = AnimalApmsCollectionConfiguration().apmsBatchTaskExecutor(ApmsScheduleSettings(environment))
        executor.initialize()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejectedRan = CountDownLatch(1)
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
