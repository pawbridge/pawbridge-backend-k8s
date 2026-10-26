package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.VisitCollectionConfiguration
import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionStore
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionController
import io.mockk.confirmVerified
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verifySequence
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
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler
import java.time.Clock
import java.time.Duration
import java.util.Base64

@ExtendWith(OutputCaptureExtension::class)
class VisitCollectionConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `수집이 켜지지 않았거나 키가 없고 읽기만 켜졌을 때__조립하면__수집도 스케줄러도 만들지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(VisitCollectionConfiguration::class.java)
            .withPropertyValues("pawbridge.visits.enabled=true")
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.visits.collection-enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(VisitCollection::class.java)).isEmpty()
            assertThat(context.getBeansOfType(VisitIdentity::class.java)).isEmpty()
            assertThat(context.getBeansOfType(VisitCollectionController::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        }
    }

    @Test
    fun `읽기가 꺼졌거나 키가 없는데 수집이 켜졌을 때__조립하면__닫힌 채 실패한다`() {
        // given
        val withoutRead = ApplicationContextRunner().withUserConfiguration(VisitCollectionConfiguration::class.java)
            .withPropertyValues("pawbridge.visits.collection-enabled=true")
        val withoutKey = inputs(false)

        // when
        withoutRead.run { context ->
            // then
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Visit collection requires pawbridge.visits.enabled=true.")
        }
        withoutKey.run { context ->
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Visit signing key must contain at least 32 bytes.")
        }
    }

    @Test
    fun `기존 쓰기 자원과 합성 키로 켜졌을 때__조립하면__한정된 생명주기 하나와 원래 트랜잭션을 쓴다`(output: CapturedOutput) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<JdbcTransactionManager>()
        val clock = mockk<Clock>()
        val service = mockk<VisitCollection>()

        justRun { service.start() }
        justRun { service.cleanup() }

        // when
        inputs(true, jdbc, manager, clock).run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(VisitCollection::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(VisitCollectionConfiguration.VisitCollectionLifecycle::class.java))
                .hasSize(1)
            val service = context.getBean(VisitCollection::class.java)
            val store = context.getBean(VisitCollectionStore::class.java)
            assertThat(ReflectionTestUtils.getField(store, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            assertThat(ReflectionTestUtils.getField(service, "clock")).isSameAs(context.getBean("readClock"))
            val tx = ReflectionTestUtils.getField(service, "transactions") as TransactionTemplate
            assertThat(tx.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
            assertThat(tx.timeout).isEqualTo(10)
            assertThat(tx.isReadOnly).isFalse()
            val scheduler = context.getBean("visitCollectionScheduler", ThreadPoolTaskScheduler::class.java)
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).hasSize(1)
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
            assertThat(scheduler.scheduledThreadPoolExecutor.removeOnCancelPolicy).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
            assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
            assertThat(ReflectionTestUtils.getField(scheduler, "acceptTasksAfterContextClose")).isEqualTo(false)
            assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(false)
            assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).isEqualTo(5000L)
            assertThat(context.beanFactory.getDependenciesForBean("visitCollectionScheduler"))
                .contains("writeDataSource")
            val tasks = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java).scheduledTasks
            assertThat(tasks).hasSize(1)
            val task = tasks.iterator().next().task as FixedDelayTask
            assertThat(task.initialDelayDuration).isEqualTo(Duration.ofSeconds(60))
            assertThat(task.intervalDuration).isEqualTo(Duration.ofSeconds(60))
            val annotation = VisitCollectionConfiguration.VisitCollectionLifecycle::class.java
                .getMethod("cleanup")
                .getAnnotation(Scheduled::class.java)
            assertThat(annotation.scheduler).isEqualTo("visitCollectionScheduler")
            confirmVerified(jdbc, manager, clock)
            val handler = ReflectionTestUtils.getField(scheduler, "errorHandler") as ErrorHandler
            handler.handleError(IllegalStateException("synthetic-secret-cookie", RuntimeException("synthetic-private-sql")))
            assertThat(output.all).contains("Visit collection cleanup failed (IllegalStateException)")
                .doesNotContain("synthetic-secret-cookie", "synthetic-private-sql")
        }
        val lifecycle = VisitCollectionConfiguration.VisitCollectionLifecycle(service)
        lifecycle.start()
        lifecycle.cleanup()
        verifySequence {
            service.start()
            service.cleanup()
            service.cleanup()
        }
    }

    private fun inputs(
        key: Boolean,
        jdbc: JdbcTemplate = mockk(),
        manager: JdbcTransactionManager = mockk(),
        clock: Clock = mockk(),
    ): ApplicationContextRunner {
        val runner = ApplicationContextRunner().withUserConfiguration(VisitCollectionConfiguration::class.java)
            .withPropertyValues("pawbridge.visits.enabled=true", "pawbridge.visits.collection-enabled=true")
            .withInitializer { context ->
                context.beanFactory.registerSingleton("writeDataSource", Any())
                context.beanFactory.registerSingleton("writeJdbc", jdbc)
                context.beanFactory.registerSingleton("writeTransactionManager", manager)
                context.beanFactory.registerSingleton("readClock", clock)
            }
        return if (key) {
            runner.withPropertyValues("pawbridge.visits.signing-key=" + Base64.getEncoder().encodeToString(ByteArray(32)))
        } else {
            runner
        }
    }
}
