package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteExpiry
import io.mockk.confirmVerified
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.FixedDelayTask
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@ExtendWith(OutputCaptureExtension::class)
class NoteExpiryConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `정리 플래그가 없거나 꺼져 있을 때__조립하면__만료와 스케줄링 빈을 등록하지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(NoteExpiryConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(NoteExpiry::class.java)).isEmpty()
            assertThat(context.getBeansOfType(NoteExpiryConfiguration.NoteExpiryJob::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        }
    }

    @Test
    fun `정리가 켜지고 기존 쓰기 입력이 있을 때__조립하면__제한된 전용 스케줄러에 고정 지연 작업을 하나 등록한다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<JdbcTransactionManager>()
        val clock = mockk<Clock>()

        // when
        enabled(jdbc, manager, clock).run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(NoteExpiry::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).hasSize(1)
            val scheduler = context.getBean("noteExpiryScheduler", ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.javaClass).isEqualTo(ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
            assertThat(scheduler.scheduledThreadPoolExecutor.removeOnCancelPolicy).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
            assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
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
            confirmVerified(jdbc, manager, clock)
        }
    }

    @Test
    fun `비공개 오류 상세가 주어졌을 때__스케줄러가 실패를 처리하면__실패 유형만 기록한다`(output: CapturedOutput) {
        // given
        val runner = enabled(mockk<JdbcTemplate>(), mockk<JdbcTransactionManager>(), mockk<Clock>())

        // when
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val scheduler = context.getBean("noteExpiryScheduler", ThreadPoolTaskScheduler::class.java)
            scheduler.schedule(
                {
                    throw IllegalStateException(
                        "private-note-body private-token DELETE FROM private_notes",
                        RuntimeException("private-cause"),
                    )
                },
                Instant.now(),
            ).get(5, TimeUnit.SECONDS)

            // then
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
