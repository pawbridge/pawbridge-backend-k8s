package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.boot.configuration.ShelterObservationConfiguration
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.CronTask
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

@ExtendWith(OutputCaptureExtension::class)
class ShelterObservationConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on"])
    fun given__missing_or_non_true_schedule_flag__when__assembling_postgresql_web_context__then__create_no_producer_or_scheduler(
        flag: String,
    ) {
        var runner = WebApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration::class.java)
            .withPropertyValues(BACKEND)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.shelter-observations.schedule-enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(JdbcShelterObservationRecorder::class.java)
                .doesNotHaveBean(ShelterObservationConfiguration.ShelterObservationJob::class.java)
                .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
                .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "elasticsearch"])
    fun given__missing_or_other_query_backend__when__enabling_web_schedule__then__create_no_producer_or_scheduler(
        backend: String,
    ) {
        var runner = WebApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration::class.java)
            .withPropertyValues(ENABLED)
        if (backend != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-query.backend=$backend")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(JdbcShelterObservationRecorder::class.java)
                .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
                .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor::class.java)
        }
    }

    @Test
    fun given__a_non_web_process__when__both_flags_match__then__create_no_producer_or_scheduler() {
        ApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration::class.java)
            .withPropertyValues(ENABLED, BACKEND)
            .run { context ->
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(JdbcShelterObservationRecorder::class.java)
                    .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor::class.java)
            }
    }

    @Test
    fun given__actual_main_and_explicit_observation_configuration__when__assembling_then_closing__then__reuse_writer_and_named_cron_without_startup_sql(
        output: CapturedOutput,
    ) {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_DB_URL"] = "jdbc:postgresql://127.0.0.1:1/pawbridge_observation_synthetic"
        val read = ReadDatabaseSettings.fromEnvironment { name -> values[name] }
        val write = PostWriteSettings.fromEnvironment { name -> values[name] }
        // Exercise the actual cron property consumer, several days away from execution in every test run.
        val futureDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3)
        val futureCron = "0 0 4 " + futureDate.dayOfMonth + " " + futureDate.monthValue + " *"
        val schedulerRef = AtomicReference<ThreadPoolTaskScheduler>()
        val delayedRef = AtomicReference<ScheduledFuture<*>>()
        val poolsRef = AtomicReference<List<HikariDataSource>>()
        WebApplicationContextRunner()
            .withUserConfiguration(PawBridgeApplication::class.java, ShelterObservationConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", read)
                context.beanFactory.registerSingleton("postWriteSettings", write)
            }
            .withPropertyValues(
                ENABLED,
                BACKEND,
                "pawbridge.shelter-observations.cron=$futureCron",
                "spring.sql.init.mode=never",
                "spring.jmx.enabled=false",
                "pawbridge.chat.enabled=false",
                "pawbridge.jobs.cleanup-enabled=false",
                "pawbridge.user-maintenance.enabled=false",
                "pawbridge.community-maintenance.enabled=false",
                "pawbridge.auth.google-enabled=false",
                "pawbridge.auth.sessions-enabled=false",
                "pawbridge.visits.enabled=false",
                "pawbridge.visits.collection-enabled=false",
                "pawbridge.animal-query.projection-schedule-enabled=false",
                "apms.batch.schedule-enabled=false",
                "apms-photo-archive.enabled=false",
                "tourapi.enabled=false",
                "tourapi.schedule-enabled=false",
                "lost-gallery-feed.enabled=false",
                "pawbridge.animal-lost-search.enabled=false",
                "pawbridge.home-videos.management-enabled=false",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(JdbcShelterObservationRecorder::class.java)
                    .hasSingleBean(ShelterObservationConfiguration.ShelterObservationJob::class.java)
                    .hasSingleBean(ThreadPoolTaskScheduler::class.java)
                val recorder = context.getBean(JdbcShelterObservationRecorder::class.java)
                val jdbc = ReflectionTestUtils.getField(recorder, "jdbc") as JdbcTemplate
                val transaction = ReflectionTestUtils.getField(recorder, "transaction") as TransactionTemplate
                assertThat(jdbc.dataSource).isSameAs(context.getBean("writeDataSource"))
                assertThat(jdbc.queryTimeout).isEqualTo(30)
                assertThat(transaction.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
                assertThat(transaction).isNotSameAs(context.getBean("writeTransaction"))
                assertThat(transaction.timeout).isEqualTo(30)
                assertThat(transaction.isReadOnly).isFalse()
                assertThat(transaction.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                assertThat(transaction.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(
                    ReflectionTestUtils.getField(
                        context.getBean(ShelterObservationConfiguration.ShelterObservationJob::class.java),
                        "recorder",
                    ),
                ).isSameAs(recorder)
                assertThat(context.getBeansOfType(DataSource::class.java)).containsOnlyKeys("dataSource")
                assertThat(context.getBean("writeDataSource")).isSameAs(context.getBean("dataSource"))
                assertThat(context.getBeansOfType(JdbcTransactionManager::class.java))
                    .containsOnlyKeys("transactionManager", "writeTransactionManager")
                assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).isEmpty()
                poolsRef.set(context.getBeansOfType(HikariDataSource::class.java).values.toList())
                poolsRef.get().forEach { pool ->
                    assertThat(pool.hikariPoolMXBean.totalConnections).isZero()
                    assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
                }
                val scheduler = context.getBean("shelterObservationScheduler", ThreadPoolTaskScheduler::class.java)
                schedulerRef.set(scheduler)
                assertThat(scheduler.threadNamePrefix).isEqualTo("shelter-observation-")
                assertThat(scheduler.submit(Callable { Thread.currentThread().isDaemon }).get(2, TimeUnit.SECONDS)).isTrue()
                assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
                assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
                assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
                assertThat(ReflectionTestUtils.getField(scheduler, "acceptTasksAfterContextClose")).isEqualTo(false)
                assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true)
                assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).isEqualTo(35000L)
                assertThat(context.beanFactory.getDependenciesForBean("shelterObservationScheduler"))
                    .contains("dataSource", "shelterObservationRecorder")
                val tasks = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java).scheduledTasks
                assertThat(tasks).hasSize(1)
                assertThat(tasks.iterator().next().task).isInstanceOf(CronTask::class.java)
                assertThat((tasks.iterator().next().task as CronTask).expression).isEqualTo(futureCron)
                val metadata = ShelterObservationConfiguration.ShelterObservationJob::class.java.getMethod("record")
                    .getAnnotation(Scheduled::class.java)
                assertThat(metadata.cron).isEqualTo("\${pawbridge.shelter-observations.cron:0 0 4 * * *}")
                assertThat(metadata.zone).isEqualTo("Asia/Seoul")
                assertThat(metadata.scheduler).isEqualTo("shelterObservationScheduler")
                val errors = ReflectionTestUtils.getField(scheduler, "errorHandler") as ErrorHandler
                errors.handleError(
                    IllegalStateException("synthetic-private-observation-SQL", RuntimeException("synthetic-private-cause")),
                )
                assertThat(output.all).contains("Shelter daily observation failed (IllegalStateException)")
                    .doesNotContain("synthetic-private-observation-SQL", "synthetic-private-cause")
                delayedRef.set(
                    scheduler.scheduleWithFixedDelay(
                        Runnable { throw AssertionError("Delayed probe must be cancelled") },
                        Instant.now().plusSeconds(60),
                        Duration.ofSeconds(60),
                    ),
                )
                assertThat(output.all).doesNotContain("Shelter daily observations recorded:")
            }
        assertThat(delayedRef.get().isCancelled).isTrue()
        assertThat(schedulerRef.get().scheduledThreadPoolExecutor.isTerminated).isTrue()
        assertThat(poolsRef.get()).hasSize(1).allSatisfy { pool -> assertThat(pool.isClosed).isTrue() }
    }

    @Test
    fun given__another_writer_transaction_manager__when__enabling_observations__then__reject_before_borrowing() {
        val source = mock(DataSource::class.java)
        val other = mock(DataSource::class.java)
        WebApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration::class.java)
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(other) })
            .withPropertyValues(ENABLED, BACKEND)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasStackTraceContaining("Shelter observations require the shared writer transaction manager")
                verifyNoInteractions(source, other)
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__a_successful_or_failed_recorder__when__scheduled_adapter_runs__then__call_once_and_preserve_failure(
        fails: Boolean,
    ) {
        val recorder = mock(JdbcShelterObservationRecorder::class.java)
        val failure = IllegalStateException("synthetic-observation-unavailable")
        if (fails) {
            whenever(recorder.recordToday()).thenThrow(failure)
        } else {
            whenever(recorder.recordToday()).thenReturn(3)
        }
        val job = ShelterObservationConfiguration.ShelterObservationJob(recorder)
        if (fails) {
            assertThatThrownBy { job.record() }.isSameAs(failure)
        } else {
            job.record()
        }
        verify(recorder).recordToday()
        verifyNoMoreInteractions(recorder)
    }

    companion object {
        private const val ENABLED = "pawbridge.shelter-observations.schedule-enabled=true"
        private const val BACKEND = "pawbridge.animal-query.backend=postgresql"
    }
}
