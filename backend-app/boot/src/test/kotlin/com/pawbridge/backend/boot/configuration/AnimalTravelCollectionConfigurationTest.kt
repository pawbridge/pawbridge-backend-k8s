package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.PawBridgeApplication
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PetTravelCollector
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiClient
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiProperties
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.springframework.aop.framework.Advised
import org.springframework.batch.core.Job
import org.springframework.batch.core.job.SimpleJob
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.tasklet.TaskletStep
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.jdbc.support.incrementer.PostgresSequenceMaxValueIncrementer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.TransactionAttribute
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier

class AnimalTravelCollectionConfigurationTest {
    @Test
    fun givenExistingGalleryAndShelterAssembly__whenTravelCollectionIsAdded__thenMainStillRegistersAllThreeFeatures() {
        val imports = PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java }
        assertThat(imports).contains(
            AnimalGalleryFeedConfiguration::class.java,
            ShelterCommandConfiguration::class.java,
            AnimalTravelCollectionConfiguration::class.java,
        )
    }

    private fun runner(source: HikariDataSource, jdbc: JdbcTemplate, manager: JdbcTransactionManager): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration::class.java,
                    ConfigurationPropertiesAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(AnimalTravelCollectionConfiguration::class.java)
            .withBean("writeDataSource", HikariDataSource::class.java, Supplier { source })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withBean("readClock", Clock::class.java, Supplier { CLOCK })
    }

    private fun source(capacity: Int): HikariDataSource {
        val source = mock(HikariDataSource::class.java)
        whenever(source.maximumPoolSize).thenReturn(capacity)
        return source
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "query-only", "schedule-only"])
    fun givenCollectionDisabledOrOnlyQueryOrScheduleSelected__whenContextStarts__thenNoCollectionComponentsOrBorrow(
        selection: String,
    ) {
        val source = source(1)
        var context = runner(source, JdbcTemplate(source), JdbcTransactionManager(source))
        if (selection == "false") {
            context = context.withPropertyValues("tourapi.enabled=false")
        }
        if (selection == "query-only") {
            context = context.withPropertyValues(
                "pawbridge.animal-travel.enabled=true",
                "pawbridge.animal-query.backend=postgresql",
            )
        }
        if (selection == "schedule-only") {
            context = context.withPropertyValues("tourapi.schedule-enabled=true")
        }
        context.run { app ->
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(TourApiClient::class.java)
                .doesNotHaveBean(TourApiProperties::class.java)
                .doesNotHaveBean(PostgresqlTravelCollectionCatalog::class.java)
                .doesNotHaveBean(PetTravelCollector::class.java)
                .doesNotHaveBean(JobRepository::class.java)
                .doesNotHaveBean(Job::class.java)
                .doesNotHaveBean(AnimalTravelCollectionSchedule::class.java)
                .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
            verify(source, never()).getConnection()
        }
    }

    @Test
    fun givenCollectionEnabledWithoutSchedule__whenContextStarts__thenExplicitPostgresqlBatchUsesSameWriterWithoutMetadataOrJobExecution() {
        val source = source(2)
        val jdbc = JdbcTemplate(source)
        val manager = JdbcTransactionManager(source)
        runner(source, jdbc, manager).withPropertyValues("tourapi.enabled=true", "tourapi.schedule-enabled=false")
            .run { app ->
                assertThat(app).hasNotFailed()
                    .hasSingleBean(TourApiClient::class.java)
                    .hasSingleBean(PostgresqlTravelCollectionCatalog::class.java)
                    .hasSingleBean(PetTravelCollector::class.java)
                    .hasSingleBean(JobRepository::class.java)
                    .hasSingleBean(Job::class.java)
                    .doesNotHaveBean(JobLauncher::class.java)
                    .doesNotHaveBean("jobLauncherApplicationRunner")
                    .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
                assertThat(app.getBean(TourApiProperties::class.java).isEnabled).isTrue()
                val catalog = app.getBean(PostgresqlTravelCollectionCatalog::class.java)
                assertThat(ReflectionTestUtils.getField(catalog, "jdbc")).isSameAs(jdbc)
                val catalogWrites = ReflectionTestUtils.getField(catalog, "transaction") as TransactionTemplate
                assertThat(catalogWrites.transactionManager).isSameAs(manager)
                val collector: Any = app.getBean(PetTravelCollector::class.java)
                assertThat(ReflectionTestUtils.getField(collector, "dataSource")).isSameAs(source)
                assertThat(ReflectionTestUtils.getField(collector, "clock")).isSameAs(CLOCK)

                val repository = app.getBean(JobRepository::class.java)
                val target: Any = AopTestUtils.getUltimateTargetObject(repository)
                for (field in arrayOf("jobInstanceDao", "jobExecutionDao", "stepExecutionDao", "ecDao")) {
                    val dao: Any = ReflectionTestUtils.getField(target, field)!!
                    assertThat(ReflectionTestUtils.getField(dao, "tablePrefix")).isEqualTo("pawbridge_animal.BATCH_")
                    val metadataJdbc = ReflectionTestUtils.getField(dao, "jdbcTemplate") as JdbcTemplate
                    assertThat(metadataJdbc.dataSource).isSameAs(source)
                }
                val instances: Any = ReflectionTestUtils.getField(target, "jobInstanceDao")!!
                val incrementer: Any = ReflectionTestUtils.getField(instances, "jobInstanceIncrementer")!!
                assertThat(incrementer).isInstanceOf(PostgresSequenceMaxValueIncrementer::class.java)
                assertThat((incrementer as PostgresSequenceMaxValueIncrementer).incrementerName)
                    .isEqualTo("pawbridge_animal.BATCH_JOB_SEQ")
                val interceptor = (repository as Advised).advisors
                    .map { advisor -> advisor.advice }
                    .filterIsInstance<TransactionInterceptor>()
                    .first()
                assertThat(interceptor.transactionManager).isSameAs(manager)

                val job = app.getBean(Job::class.java) as SimpleJob
                assertThat(job.name).isEqualTo("petTravelCollectionJob")
                assertThat(job.stepNames).containsExactly("petTravelCollectionStep")
                val step = job.getStep("petTravelCollectionStep") as TaskletStep
                assertThat(ReflectionTestUtils.getField(step, "transactionManager")).isSameAs(manager)
                val attribute = ReflectionTestUtils.getField(step, "transactionAttribute") as TransactionAttribute
                assertThat(attribute.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED)
                verify(source, never()).getConnection()
            }
    }

    @Test
    fun givenOnlyOneWriterConnection__whenCollectionEnabled__thenFailBeforeMetadataOrBorrow() {
        val source = source(1)
        runner(source, JdbcTemplate(source), JdbcTransactionManager(source))
            .withPropertyValues("tourapi.enabled=true").run { app ->
                assertThat(app).hasFailed()
                assertThat(app.startupFailure).hasRootCauseMessage(
                    "Travel collection requires the same writer with capacity of at least two",
                )
                verify(source, never()).getConnection()
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jdbc", "transaction-manager"])
    fun givenAnotherWriterDataSource__whenCollectionEnabled__thenRejectMixedOwnershipBeforeBorrow(mismatch: String) {
        val source = source(2)
        val other = source(2)
        val jdbc = JdbcTemplate(if (mismatch == "jdbc") other else source)
        val manager = JdbcTransactionManager(if (mismatch == "transaction-manager") other else source)
        runner(source, jdbc, manager).withPropertyValues("tourapi.enabled=true").run { app ->
            assertThat(app).hasFailed()
            assertThat(app.startupFailure).hasRootCauseMessage(
                "Travel collection requires the same writer with capacity of at least two",
            )
            verify(source, never()).getConnection()
            verify(other, never()).getConnection()
        }
    }

    @Test
    fun givenCollectionAndScheduleEnabled__whenContextStarts__thenOneNamedSeoulSchedulerWaitsOnNormalShutdownWithoutLaunchingJob() {
        val source = source(2)
        runner(source, JdbcTemplate(source), JdbcTransactionManager(source))
            // A future cron prevents this composition test from invoking the actual Job.
            .withPropertyValues("tourapi.enabled=true", "tourapi.schedule-enabled=true", "tourapi.collection-cron=0 0 0 1 1 *")
            .run { app ->
                assertThat(app).hasNotFailed()
                    .hasSingleBean(AnimalTravelCollectionSchedule::class.java)
                    .hasSingleBean(ThreadPoolTaskScheduler::class.java)
                val scheduler = app.getBean("tourApiCollectionScheduler", ThreadPoolTaskScheduler::class.java)
                assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
                assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
                assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
                assertThat(scheduler.scheduledThreadPoolExecutor.removeOnCancelPolicy).isTrue()
                assertThat(scheduler.threadNamePrefix).isEqualTo("pawbridge-tourapi-")
                assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true)
                assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis"))
                    .isEqualTo(Integer.MAX_VALUE.toLong() * 1000)
                val scheduled = AnimalTravelCollectionSchedule::class.java.getDeclaredMethod("collect")
                    .getAnnotation(Scheduled::class.java)
                assertThat(scheduled.scheduler).isEqualTo("tourApiCollectionScheduler")
                assertThat(scheduled.zone).isEqualTo("Asia/Seoul")
                assertThat(scheduled.cron).isEqualTo("\${tourapi.collection-cron:0 */30 * * * *}")
                val dependencies = AnimalTravelCollectionSchedule::class.java.getDeclaredMethod("tourApiCollectionScheduler")
                    .getAnnotation(DependsOn::class.java)
                assertThat(dependencies.value).containsExactlyInAnyOrder("writeDataSource", "petTravelCollectionJob")
                verify(source, never()).getConnection()
            }
    }

    companion object {
        private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC)
    }
}
