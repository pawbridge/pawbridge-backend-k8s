package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteExpiry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler
import java.time.Clock
import java.time.Duration

@ExtendWith(OutputCaptureExtension::class)
class NoteExpiryConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun givenDisabledOrAbsentCleanupFlag__whenAssembling__thenRegisterNoExpiryOrSchedulingBeans(flag: String) {
        var runner = ApplicationContextRunner().withUserConfiguration(NoteExpiryConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=$flag")
        }

        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(NoteExpiry::class.java)).isEmpty()
            assertThat(context.getBeansOfType(NoteExpiryConfiguration.NoteExpiryJob::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        }
    }

    @Test
    fun givenEnabledCleanupAndExistingWriterInputs__whenAssembling__thenUseDedicatedTransactionAndBoundedQualifiedScheduler() {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(JdbcTransactionManager::class.java)
        val clock = mock(Clock::class.java)
        enabled(jdbc, manager, clock).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(NoteExpiry::class.java)).hasSize(1)
            val expiry = context.getBean(NoteExpiry::class.java)
            assertThat(ReflectionTestUtils.getField(expiry, "jdbc")).isSameAs(jdbc)
            assertThat(ReflectionTestUtils.getField(expiry, "clock")).isSameAs(clock)
            val transaction = ReflectionTestUtils.getField(expiry, "transaction") as TransactionTemplate
            assertThat(transaction.transactionManager).isSameAs(manager)
            assertThat(transaction.timeout).isEqualTo(15)
            assertThat(transaction.isReadOnly).isFalse()
            val job = context.getBean(NoteExpiryConfiguration.NoteExpiryJob::class.java)
            assertThat(ReflectionTestUtils.getField(job, "expiry")).isSameAs(expiry)

            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).hasSize(1)
            val scheduler = context.getBean("noteExpiryScheduler", ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.javaClass).isEqualTo(ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
            assertThat(scheduler.scheduledThreadPoolExecutor.removeOnCancelPolicy).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
            assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
            assertThat(ReflectionTestUtils.getField(scheduler, "acceptTasksAfterContextClose")).isEqualTo(false)
            assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(false)
            assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).isEqualTo(5000L)
            assertThat(context.beanFactory.getDependenciesForBean("noteExpiryScheduler")).contains("writeDataSource")

            val processor = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java)
            assertThat(processor.scheduledTasks).hasSize(1)
            val task = processor.scheduledTasks.iterator().next().task
            assertThat(task).isInstanceOf(FixedDelayTask::class.java)
            val fixedDelay = task as FixedDelayTask
            assertThat(fixedDelay.initialDelayDuration).isEqualTo(Duration.ofMillis(300000))
            assertThat(fixedDelay.intervalDuration).isEqualTo(Duration.ofMillis(300000))
            val metadata = NoteExpiryConfiguration.NoteExpiryJob::class.java.getMethod("expire")
                .getAnnotation(Scheduled::class.java)
            assertThat(metadata.scheduler).isEqualTo("noteExpiryScheduler")
            assertThat(metadata.initialDelay).isEqualTo(300000L)
            assertThat(metadata.fixedDelay).isEqualTo(300000L)
            verifyNoInteractions(jdbc, manager, clock)
        }
    }

    @Test
    fun givenPrivateFailureDetails__whenSchedulerHandlesFailure__thenLogOnlyTheFailureType(output: CapturedOutput) {
        enabled(mock(JdbcTemplate::class.java), mock(JdbcTransactionManager::class.java), mock(Clock::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                val scheduler = context.getBean("noteExpiryScheduler", ThreadPoolTaskScheduler::class.java)
                val handler = ReflectionTestUtils.getField(scheduler, "errorHandler") as ErrorHandler
                handler.handleError(
                    IllegalStateException(
                        "private-note-body private-token DELETE FROM private_notes",
                        RuntimeException("private-cause"),
                    ),
                )
                assertThat(output.all).contains("Private note expiry failed (IllegalStateException)")
                    .doesNotContain(
                        "private-note-body",
                        "private-token",
                        "DELETE FROM",
                        "private-cause",
                        "IllegalStateException:",
                    )
            }
    }

    private fun enabled(jdbc: JdbcTemplate, manager: JdbcTransactionManager, clock: Clock): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withUserConfiguration(NoteExpiryConfiguration::class.java)
            .withPropertyValues("pawbridge.jobs.cleanup-enabled=true")
            .withInitializer { context ->
                context.beanFactory.registerSingleton("writeDataSource", Any())
                context.beanFactory.registerSingleton("writeJdbc", jdbc)
                context.beanFactory.registerSingleton("writeTransactionManager", manager)
                context.beanFactory.registerSingleton("readClock", clock)
            }
    }
}
