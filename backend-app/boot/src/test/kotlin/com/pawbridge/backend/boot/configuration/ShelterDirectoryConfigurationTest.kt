package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.PawBridgeApplication
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryClient
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryCollector
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryStore
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.batch.core.Job
import org.springframework.batch.core.job.SimpleJob
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.tasklet.TaskletStep
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.TransactionAttribute
import java.util.function.Supplier

class ShelterDirectoryConfigurationTest {
    @Test
    fun givenMainImports__whenShelterDirectoryIsAdded__thenMainRegistersTheExplicitConfiguration() {
        val imports = PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java }
        assertThat(imports).contains(
            ShelterDirectoryConfiguration::class.java,
            AnimalTravelCollectionConfiguration::class.java,
        )
    }

    private fun runner(source: HikariDataSource, manager: JdbcTransactionManager): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(ShelterDirectoryConfiguration::class.java)
            .withBean("writeDataSource", HikariDataSource::class.java, Supplier { source })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(source) })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "key-only"])
    fun givenScheduleSwitchNotTrue__whenContextStarts__thenNoClientJobSchedulerAnalyzerOrBorrow(selection: String) {
        val source = mock(HikariDataSource::class.java)
        var context = runner(source, JdbcTransactionManager(source))
        if (selection == "false") {
            context = context.withPropertyValues("shelter-directory.schedule-enabled=false")
        }
        if (selection == "on") {
            context = context.withPropertyValues("shelter-directory.schedule-enabled=on")
        }
        if (selection == "key-only") {
            context = context.withPropertyValues("shelter-directory.service-key=test-key")
        }
        context.run { app ->
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(ShelterDirectoryClient::class.java)
                .doesNotHaveBean(ShelterDirectoryStore::class.java)
                .doesNotHaveBean(ShelterDirectoryCollector::class.java)
                .doesNotHaveBean(JobRepository::class.java)
                .doesNotHaveBean(Job::class.java)
                .doesNotHaveBean(ShelterDirectorySchedule::class.java)
                .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
            verify(source, never()).getConnection()
        }
    }

    @Test
    fun givenScheduleSwitchTrue__whenContextStarts__thenOriginalJobUsesSameWriterAndOneSeoulSchedulerWithoutLaunching() {
        val source = mock(HikariDataSource::class.java)
        val manager = JdbcTransactionManager(source)
        // A future cron prevents this composition test from invoking the actual Job.
        runner(source, manager).withPropertyValues(
            "shelter-directory.schedule-enabled=true",
            "shelter-directory.collection-cron=0 0 0 1 1 *",
        )
            .run { app ->
                assertThat(app).hasNotFailed()
                    .hasSingleBean(ShelterDirectoryClient::class.java)
                    .hasSingleBean(ShelterDirectoryStore::class.java)
                    .hasSingleBean(ShelterDirectoryCollector::class.java)
                    .hasSingleBean(JobRepository::class.java)
                    .hasSingleBean(Job::class.java)
                    .hasSingleBean(ShelterDirectorySchedule::class.java)
                    .hasSingleBean(ThreadPoolTaskScheduler::class.java)
                    .hasSingleBean(KoreanSearchAnalyzer::class.java)
                assertThat(ReflectionTestUtils.getField(app.getBean(ShelterDirectoryClient::class.java), "key"))
                    .isEqualTo("")
                assertThat(ReflectionTestUtils.getField(app.getBean(ShelterDirectoryCollector::class.java), "source"))
                    .isSameAs(source)
                val search: Any =
                    ReflectionTestUtils.getField(app.getBean(ShelterDirectoryStore::class.java), "searchDocuments")!!
                assertThat(ReflectionTestUtils.getField(search, "analyzer"))
                    .isSameAs(app.getBean(KoreanSearchAnalyzer::class.java))

                val target: Any = AopTestUtils.getUltimateTargetObject(app.getBean(JobRepository::class.java))
                val dao: Any = ReflectionTestUtils.getField(target, "jobExecutionDao")!!
                assertThat(ReflectionTestUtils.getField(dao, "tablePrefix")).isEqualTo("pawbridge_animal.BATCH_")
                assertThat((ReflectionTestUtils.getField(dao, "jdbcTemplate") as JdbcTemplate).dataSource)
                    .isSameAs(source)

                val job = app.getBean(Job::class.java) as SimpleJob
                assertThat(job.name).isEqualTo("shelterDirectoryJob")
                assertThat(job.stepNames).containsExactly("shelterDirectoryStep")
                val step = job.getStep("shelterDirectoryStep") as TaskletStep
                assertThat(ReflectionTestUtils.getField(step, "transactionManager")).isSameAs(manager)
                val attribute = ReflectionTestUtils.getField(step, "transactionAttribute") as TransactionAttribute
                assertThat(attribute.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED)

                val scheduler = app.getBean("shelterDirectoryScheduler", ThreadPoolTaskScheduler::class.java)
                assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
                assertThat(scheduler.threadNamePrefix).isEqualTo("pawbridge-shelter-directory-")
                assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true)
                verify(source, never()).getConnection()
            }
    }

    @Test
    fun givenAnotherWriterDataSource__whenScheduleEnabled__thenRejectMixedOwnershipBeforeBorrow() {
        val source = mock(HikariDataSource::class.java)
        val other = mock(HikariDataSource::class.java)
        runner(source, JdbcTransactionManager(other)).withPropertyValues("shelter-directory.schedule-enabled=true")
            .run { app ->
                assertThat(app).hasFailed()
                assertThat(app.startupFailure).hasRootCauseMessage(
                    "Shelter directory must use the same existing writer DataSource",
                )
                verify(source, never()).getConnection()
                verify(other, never()).getConnection()
            }
    }

    @Test
    fun givenScheduleMethod__whenReadingContract__thenKeepsOriginalCronZoneOnNamedScheduler() {
        val scheduled = ShelterDirectorySchedule::class.java.getDeclaredMethod("collect").getAnnotation(Scheduled::class.java)
        assertThat(scheduled.cron).isEqualTo("\${shelter-directory.collection-cron:0 0 4 * * *}")
        assertThat(scheduled.zone).isEqualTo("Asia/Seoul")
        assertThat(scheduled.scheduler).isEqualTo("shelterDirectoryScheduler")
        val dependencies = ShelterDirectorySchedule::class.java.getDeclaredMethod("shelterDirectoryScheduler")
            .getAnnotation(DependsOn::class.java)
        assertThat(dependencies.value).containsExactlyInAnyOrder("writeDataSource", "shelterDirectoryJob")
    }
}
