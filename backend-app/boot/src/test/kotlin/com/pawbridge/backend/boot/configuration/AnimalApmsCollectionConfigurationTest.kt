package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.PawBridgeApplication
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchExecutionRecovery
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchProperties
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlanFactory
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.springframework.aop.framework.Advised
import org.springframework.batch.core.Job
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mock.env.MockEnvironment
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

class AnimalApmsCollectionConfigurationTest {
    private val context = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
        .withUserConfiguration(AnimalApmsCollectionConfiguration::class.java)

    @Test
    fun givenActualMainAssembly__whenImportsAreRead__thenApmsScheduleCompositionIsRegistered() {
        val imports = PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java }
        assertThat(imports).contains(AnimalApmsCollectionConfiguration::class.java)
    }

    @Test
    fun givenMissingFlag__whenContextStarts__thenNoApmsDependenciesAreRequired() {
        context.run { application ->
            assertThat(application).hasNotFailed()
            assertThat(application)
                .doesNotHaveBean(ApmsApiClient::class.java)
                .doesNotHaveBean(Job::class.java)
                .doesNotHaveBean(JobRepository::class.java)
                .doesNotHaveBean(JobExplorer::class.java)
                .doesNotHaveBean(ApmsCollectionSchedule::class.java)
                .doesNotHaveBean("apmsJobScope")
                .doesNotHaveBean("apmsStepScope")
                .doesNotHaveBean("apmsBatchTaskExecutor")
        }
    }

    @Test
    fun givenExplicitOff__whenContextStarts__thenInvalidApmsValuesAreNotBound() {
        context.withPropertyValues(
            "apms.batch.schedule-enabled=false",
            "apms.api.base-url=invalid",
            "apms.batch.workers=0",
        ).run { application ->
            assertThat(application).hasNotFailed().doesNotHaveBean(ApmsScheduleSettings::class.java)
        }
    }

    @Test
    fun givenApmsOnAndTourOff__whenComposed__thenNamedMetadataAndSynchronousLauncherNeedNoConnection() {
        context.withUserConfiguration(WriterFixture::class.java).withPropertyValues(
            "apms.batch.schedule-enabled=true", "tourapi.enabled=false", "tourapi.schedule-enabled=false",
            "apms.api.base-url=https://provider.example.test/openapi", "apms.api.service-key=fixture-key",
            "apms.batch.collection-cron=0 0 0 1 1 *", "apms.batch.stale-execution-threshold=11m",
        )
            .run { application ->
                assertThat(application).hasNotFailed()
                    .hasBean("apmsJobRepository")
                    .hasBean("apmsJobExplorer")
                    .hasBean("apmsJobLauncher")
                    .hasBean("apmsAnimalSyncJob")
                    .hasBean("apmsCollectionSchedule")
                    .doesNotHaveBean("travelCollectionJobRepository")
                    .doesNotHaveBean("petTravelCollectionJob")
                assertThat(application).hasSingleBean(KoreanSearchAnalyzer::class.java)
                assertThat(
                    application.sourceApplicationContext.beanFactory.getBeanDefinition("scopedTarget.apmsAnimalSnapshot").scope,
                ).isEqualTo("job")
                assertThat(
                    application.sourceApplicationContext.beanFactory.getBeanDefinition("scopedTarget.apmsItemReader").scope,
                ).isEqualTo("step")
                assertThat(
                    application.sourceApplicationContext.beanFactory
                        .getBeanDefinition("scopedTarget.apmsAnimalItemProcessor").scope,
                ).isEqualTo("step")
                val settings = application.getBean(ApmsScheduleSettings::class.java)
                assertThat(settings.workers()).isEqualTo(1)
                assertThat(settings.transactionTimeout()).isEqualTo(15)
                val animalManager = application.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(animalManager.defaultTimeout).isEqualTo(15)
                assertMetadataManager(application.getBean("apmsJobRepository"), animalManager)
                assertMetadataManager(application.getBean("apmsJobExplorer"), animalManager)
                val recoveryTransactions = ReflectionTestUtils.getField(
                    application.getBean(ApmsBatchExecutionRecovery::class.java),
                    "transactions",
                ) as TransactionTemplate
                assertThat(recoveryTransactions.transactionManager).isSameAs(animalManager)
                assertThat(application.getBean("apmsApiClient")).isInstanceOf(AutoCloseable::class.java)
                assertThat(
                    AnimalApmsCollectionConfiguration::class.java
                        .getDeclaredMethod("apmsApiClient", ApmsScheduleSettings::class.java, ObjectMapper::class.java)
                        .getAnnotation(Bean::class.java).destroyMethod,
                ).isEqualTo("close")
                assertThat(application.getBean(ApmsBatchProperties::class.java).getStaleExecutionThreshold())
                    .isEqualTo(Duration.ofMinutes(11))
                val planClock = application.getBean("apmsClock", Clock::class.java)
                val metadataClock = application.getBean("apmsMetadataClock", Clock::class.java)
                assertThat(planClock.zone).isEqualTo(ZoneId.of("Asia/Seoul"))
                assertThat(metadataClock.zone).isEqualTo(ZoneId.systemDefault())
                assertThat(ReflectionTestUtils.getField(application.getBean(ApmsSyncPlanFactory::class.java), "clock"))
                    .isSameAs(planClock)
                assertThat(ReflectionTestUtils.getField(application.getBean(ApmsBatchExecutionRecovery::class.java), "apmsClock"))
                    .isSameAs(metadataClock)
                val executor = application.getBean(AnimalApmsCollectionConfiguration.ApmsChunkExecutor::class.java)
                assertThat(executor.corePoolSize).isEqualTo(1)
                assertThat(executor.maxPoolSize).isEqualTo(1)
                assertThat(executor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(4)
                val launcher = application.getBean("apmsJobLauncher", TaskExecutorJobLauncher::class.java)
                assertThat(ReflectionTestUtils.getField(launcher, "taskExecutor")).isInstanceOf(SyncTaskExecutor::class.java)
                verify(application.getBean(HikariDataSource::class.java), never()).getConnection()
            }
    }

    @Test
    fun givenInsufficientPool__whenEnabled__thenFailBeforeMetadataOrProviderCalls() {
        context.withUserConfiguration(WriterFixture::class.java).withPropertyValues(
            "apms.batch.schedule-enabled=true", "apms.batch.workers=2",
            "apms.api.base-url=https://provider.example.test", "apms.api.service-key=fixture-key",
        )
            .run { application ->
                assertThat(application.startupFailure)
                    .hasRootCauseMessage("APMS writer capacity must cover workers, session lock and metadata")
            }
    }

    @Test
    fun givenWorkerAndTimeoutBounds__whenBound__thenOnlyPositiveTimeoutAndOneToFourWorkersAreAccepted() {
        for (workers in intArrayOf(0, 5)) {
            val environment = environment().withProperty("apms.batch.workers", Integer.toString(workers))
            assertThatThrownBy { ApmsScheduleSettings(environment) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        for (workers in intArrayOf(1, 4)) {
            val settings = ApmsScheduleSettings(environment().withProperty("apms.batch.workers", Integer.toString(workers)))
            settings.requireCapacity(workers + 2)
            assertThatThrownBy { settings.requireCapacity(workers + 1) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy {
            ApmsScheduleSettings(environment().withProperty("apms.batch.step-timeout-seconds", "0"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            ApmsScheduleSettings(environment().withProperty("apms.batch.step-timeout-seconds", "9")).transactionTimeout(),
        ).isEqualTo(9)
        assertThat(ApmsBatchProperties().getStaleExecutionThreshold()).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun givenDifferentSearchWriter__whenComposed__thenRejectMixedDataSources() {
        val source = mock(HikariDataSource::class.java)
        whenever(source.maximumPoolSize).thenReturn(3)
        val jpa = mock(JpaTransactionManager::class.java)
        whenever(jpa.dataSource).thenReturn(source)
        val otherSearch = SearchDocumentWriter(mock(HikariDataSource::class.java), mock(KoreanSearchAnalyzer::class.java))
        assertThatThrownBy {
            AnimalApmsCollectionConfiguration().apmsScheduleSettings(
                environment(), source, JdbcTemplate(source), jpa, otherSearch,
            )
        }.hasMessage("APMS must use the same existing writer DataSource")
    }

    private fun environment(): MockEnvironment {
        return MockEnvironment().withProperty("apms.api.base-url", "https://provider.example.test/openapi")
            .withProperty("apms.api.service-key", "fixture-key")
    }

    private fun assertMetadataManager(metadata: Any, expected: JpaTransactionManager) {
        val interceptor = (metadata as Advised).advisors
            .map { advisor -> advisor.advice }
            .filterIsInstance<TransactionInterceptor>()
            .first()
        assertThat(interceptor.transactionManager).isSameAs(expected)
    }

    @Configuration(proxyBeanMethods = false)
    class WriterFixture {
        @Bean
        fun writeDataSource(): HikariDataSource {
            val source = mock(HikariDataSource::class.java)
            whenever(source.maximumPoolSize).thenReturn(3)
            return source
        }

        @Bean
        fun writeJdbc(source: HikariDataSource): JdbcTemplate = JdbcTemplate(source)

        @Bean
        fun writeTransactionManager(source: HikariDataSource): JdbcTransactionManager = JdbcTransactionManager(source)

        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()
    }
}
