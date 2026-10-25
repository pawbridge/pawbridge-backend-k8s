package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.PawBridgeApplication
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalSearchProjectionConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["missing", "false", "on", "yes", "backend", "analyzed"])
    fun missing_or_nonliteral_inputs_do_not_create_a_job_or_borrow_a_connection(missing: String) {
        val source: DataSource = mock(DataSource::class.java)
        var runner: WebApplicationContextRunner = runner(source)
        if (missing == "backend") {
            runner = runner.withPropertyValues(ENABLED, ANALYZED, "pawbridge.animal-query.backend=elasticsearch")
        } else if (missing == "analyzed") {
            runner = runner.withPropertyValues(ENABLED, BACKEND, "pawbridge.animal-query.analyzed-text=false")
        } else if (missing == "missing") {
            runner = runner.withPropertyValues(BACKEND, ANALYZED)
        } else {
            runner = runner.withPropertyValues(
                BACKEND,
                ANALYZED,
                "pawbridge.animal-query.projection-schedule-enabled=$missing",
            )
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(PostgresqlSearchProjector::class.java)
                .doesNotHaveBean(AnimalSearchProjectionJobs::class.java)
                .doesNotHaveBean("animalSearchProjectionScheduler")
            verifyNoInteractions(source)
        }
    }

    @Test
    fun non_web_process_does_not_schedule_projection_work() {
        ApplicationContextRunner().withUserConfiguration(AnimalSearchProjectionConfiguration::class.java)
            .withPropertyValues(ENABLED, BACKEND, ANALYZED)
            .run { context ->
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(PostgresqlSearchProjector::class.java)
                    .doesNotHaveBean(AnimalSearchProjectionJobs::class.java)
            }
    }

    @Test
    fun enabled_servlet_context_uses_existing_resources_and_one_named_scheduler() {
        val source: DataSource = mock(DataSource::class.java)
        whenever(source.connection).thenThrow(SQLException("owned-test-unavailable"))
        runner(source).withPropertyValues(ENABLED, BACKEND, ANALYZED).run { context ->
            assertThat(context).hasNotFailed()
                .hasSingleBean(PostgresqlSearchProjector::class.java)
                .hasSingleBean(AnimalSearchProjectionJobs::class.java)
                .hasSingleBean(KoreanSearchAnalyzer::class.java)
            assertThat(context.getBean(KoreanSearchAnalyzer::class.java))
                .isSameAs(context.getBean("animalDiscoveryAnalyzer"))
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(JdbcTransactionManager::class.java)).hasSize(1)
            val scheduler = context.getBean("animalSearchProjectionScheduler", ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.threadNamePrefix).isEqualTo("animal-search-projection-")
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
        }
    }

    @Test
    fun existing_feature_and_projection_imports_share_one_real_analyzer() {
        val source: DataSource = mock(DataSource::class.java)
        whenever(source.connection).thenThrow(SQLException("owned-test-unavailable"))
        runner(source).withUserConfiguration(AnimalSearchConfiguration::class.java)
            .withPropertyValues(ENABLED, BACKEND, ANALYZED, "pawbridge.animal-commands.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(KoreanSearchAnalyzer::class.java)
                    .hasSingleBean(PostgresqlSearchProjector::class.java)
                assertThat(context.getBean(KoreanSearchAnalyzer::class.java))
                    .isSameAs(context.getBean("animalDiscoveryAnalyzer"))
            }
    }

    @Test
    fun other_writer_manager_is_rejected_before_a_connection_is_borrowed() {
        val source: DataSource = mock(DataSource::class.java)
        val other: DataSource = mock(DataSource::class.java)
        assertThatIllegalArgumentException().isThrownBy {
            AnimalSearchProjectionConfiguration()
                .animalSearchProjector(source, JdbcTransactionManager(other), mock(KoreanSearchAnalyzer::class.java))
        }
        verifyNoInteractions(source, other)
    }

    @Test
    fun scheduler_worker_is_daemon_and_shutdown_cancels_delayed_work() {
        val scheduler = AnimalSearchProjectionConfiguration().animalSearchProjectionScheduler()
        scheduler.initialize()
        try {
            assertThat(scheduler.submit(Callable { Thread.currentThread().isDaemon }).get(2, TimeUnit.SECONDS)).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
            assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
            val delayed = scheduler.scheduleWithFixedDelay(
                Runnable { throw AssertionError("must not start") },
                Instant.now().plusSeconds(60),
                Duration.ofSeconds(60),
            )
            scheduler.shutdown()
            assertThat(delayed!!.isCancelled).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.isShutdown).isTrue()
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun main_keeps_existing_archive_and_collection_imports_when_adding_maintenance() {
        assertThat(PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java })
            .contains(
                AnimalSearchProjectionConfiguration::class.java,
                AnimalPhotoArchiveConfiguration::class.java,
                AnimalApmsCollectionConfiguration::class.java,
                AnimalTravelCollectionConfiguration::class.java,
                AnimalGalleryFeedConfiguration::class.java,
                ShelterCommandConfiguration::class.java,
            )
    }

    private fun runner(source: DataSource): WebApplicationContextRunner {
        return WebApplicationContextRunner().withUserConfiguration(AnimalSearchProjectionConfiguration::class.java)
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { JdbcTransactionManager(source) },
            )
    }

    companion object {
        private const val ENABLED = "pawbridge.animal-query.projection-schedule-enabled=true"
        private const val BACKEND = "pawbridge.animal-query.backend=postgresql"
        private const val ANALYZED = "pawbridge.animal-query.analyzed-text=true"
    }
}
