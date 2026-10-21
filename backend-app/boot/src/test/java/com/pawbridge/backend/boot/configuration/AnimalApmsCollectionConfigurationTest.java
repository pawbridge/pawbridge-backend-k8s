package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchProperties;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchExecutionRecovery;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlanFactory;
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import org.springframework.aop.framework.Advised;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class AnimalApmsCollectionConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AnimalApmsCollectionConfiguration.class);

    @Test void givenActualMainAssembly__whenImportsAreRead__thenApmsScheduleCompositionIsRegistered() {
        var imports = com.pawbridge.backend.PawBridgeApplication.class
                .getAnnotation(org.springframework.context.annotation.Import.class).value();
        assertThat(imports).contains(AnimalApmsCollectionConfiguration.class);
    }

    @Test void givenMissingFlag__whenContextStarts__thenNoApmsDependenciesAreRequired() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(ApmsApiClient.class).doesNotHaveBean(Job.class)
                    .doesNotHaveBean(JobRepository.class).doesNotHaveBean(JobExplorer.class)
                    .doesNotHaveBean(ApmsCollectionSchedule.class).doesNotHaveBean("apmsJobScope")
                    .doesNotHaveBean("apmsStepScope")
                    .doesNotHaveBean("apmsBatchTaskExecutor");
        });
    }

    @Test void givenExplicitOff__whenContextStarts__thenInvalidApmsValuesAreNotBound() {
        context.withPropertyValues("apms.batch.schedule-enabled=false", "apms.api.base-url=invalid",
                "apms.batch.workers=0").run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(ApmsScheduleSettings.class);
        });
    }

    @Test void givenApmsOnAndTourOff__whenComposed__thenNamedMetadataAndSynchronousLauncherNeedNoConnection() {
        context.withUserConfiguration(WriterFixture.class).withPropertyValues(
                "apms.batch.schedule-enabled=true", "tourapi.enabled=false", "tourapi.schedule-enabled=false",
                "apms.api.base-url=https://provider.example.test/openapi", "apms.api.service-key=fixture-key",
                "apms.batch.collection-cron=0 0 0 1 1 *", "apms.batch.stale-execution-threshold=11m")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasBean("apmsJobRepository").hasBean("apmsJobExplorer")
                            .hasBean("apmsJobLauncher").hasBean("apmsAnimalSyncJob").hasBean("apmsCollectionSchedule")
                            .doesNotHaveBean("travelCollectionJobRepository").doesNotHaveBean("petTravelCollectionJob");
                    assertThat(application).hasSingleBean(KoreanSearchAnalyzer.class);
                    assertThat(application.getSourceApplicationContext().getBeanFactory()
                            .getBeanDefinition("scopedTarget.apmsAnimalSnapshot").getScope()).isEqualTo("job");
                    assertThat(application.getSourceApplicationContext().getBeanFactory()
                            .getBeanDefinition("scopedTarget.apmsItemReader").getScope()).isEqualTo("step");
                    assertThat(application.getSourceApplicationContext().getBeanFactory()
                            .getBeanDefinition("scopedTarget.apmsAnimalItemProcessor").getScope()).isEqualTo("step");
                    var settings = application.getBean(ApmsScheduleSettings.class);
                    assertThat(settings.workers()).isEqualTo(1);
                    assertThat(settings.transactionTimeout()).isEqualTo(15);
                    var animalManager = application.getBean("animalCommandJpaTransactionManager", JpaTransactionManager.class);
                    assertThat(animalManager.getDefaultTimeout()).isEqualTo(15);
                    assertMetadataManager(application.getBean("apmsJobRepository"), animalManager);
                    assertMetadataManager(application.getBean("apmsJobExplorer"), animalManager);
                    var recoveryTransactions = (TransactionTemplate) ReflectionTestUtils.getField(
                            application.getBean(ApmsBatchExecutionRecovery.class), "transactions");
                    assertThat(recoveryTransactions.getTransactionManager()).isSameAs(animalManager);
                    assertThat(application.getBean("apmsApiClient")).isInstanceOf(AutoCloseable.class);
                    try {
                        assertThat(AnimalApmsCollectionConfiguration.class.getDeclaredMethod("apmsApiClient",
                                ApmsScheduleSettings.class, ObjectMapper.class).getAnnotation(Bean.class).destroyMethod())
                                .isEqualTo("close");
                    } catch (ReflectiveOperationException missing) { throw new AssertionError(missing); }
                    assertThat(application.getBean(ApmsBatchProperties.class).getStaleExecutionThreshold())
                            .isEqualTo(Duration.ofMinutes(11));
                    Clock planClock = application.getBean("apmsClock", Clock.class);
                    Clock metadataClock = application.getBean("apmsMetadataClock", Clock.class);
                    assertThat(planClock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
                    assertThat(metadataClock.getZone()).isEqualTo(ZoneId.systemDefault());
                    assertThat(ReflectionTestUtils.getField(application.getBean(ApmsSyncPlanFactory.class), "clock"))
                            .isSameAs(planClock);
                    assertThat(ReflectionTestUtils.getField(application.getBean(ApmsBatchExecutionRecovery.class), "apmsClock"))
                            .isSameAs(metadataClock);
                    var executor = application.getBean(AnimalApmsCollectionConfiguration.ApmsChunkExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(1);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(1);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(4);
                    var launcher = application.getBean("apmsJobLauncher", TaskExecutorJobLauncher.class);
                    assertThat(ReflectionTestUtils.getField(launcher, "taskExecutor"))
                            .isInstanceOf(org.springframework.core.task.SyncTaskExecutor.class);
                    try { verify(application.getBean(HikariDataSource.class), never()).getConnection(); }
                    catch (java.sql.SQLException impossible) { throw new AssertionError(impossible); }
                });
    }

    @Test void givenInsufficientPool__whenEnabled__thenFailBeforeMetadataOrProviderCalls() {
        context.withUserConfiguration(WriterFixture.class).withPropertyValues(
                "apms.batch.schedule-enabled=true", "apms.batch.workers=2",
                "apms.api.base-url=https://provider.example.test", "apms.api.service-key=fixture-key")
                .run(application -> assertThat(application.getStartupFailure())
                        .hasRootCauseMessage("APMS writer capacity must cover workers, session lock and metadata"));
    }

    @Test void givenWorkerAndTimeoutBounds__whenBound__thenOnlyPositiveTimeoutAndOneToFourWorkersAreAccepted() {
        for (int workers : new int[] {0, 5}) {
            var environment = environment().withProperty("apms.batch.workers", Integer.toString(workers));
            assertThatThrownBy(() -> new ApmsScheduleSettings(environment)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int workers : new int[] {1, 4}) {
            var settings = new ApmsScheduleSettings(environment().withProperty("apms.batch.workers", Integer.toString(workers)));
            settings.requireCapacity(workers + 2);
            assertThatThrownBy(() -> settings.requireCapacity(workers + 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ApmsScheduleSettings(environment().withProperty("apms.batch.step-timeout-seconds", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ApmsScheduleSettings(environment().withProperty("apms.batch.step-timeout-seconds", "9"))
                .transactionTimeout()).isEqualTo(9);
        assertThat(new ApmsBatchProperties().getStaleExecutionThreshold()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test void givenDifferentSearchWriter__whenComposed__thenRejectMixedDataSources() {
        var source = mock(HikariDataSource.class);
        when(source.getMaximumPoolSize()).thenReturn(3);
        var jpa = mock(JpaTransactionManager.class);
        when(jpa.getDataSource()).thenReturn(source);
        var otherSearch = new SearchDocumentWriter(mock(HikariDataSource.class), mock(KoreanSearchAnalyzer.class));
        assertThatThrownBy(() -> new AnimalApmsCollectionConfiguration().apmsScheduleSettings(
                environment(), source, new JdbcTemplate(source), jpa, otherSearch))
                .hasMessage("APMS must use the same existing writer DataSource");
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("apms.api.base-url", "https://provider.example.test/openapi")
                .withProperty("apms.api.service-key", "fixture-key");
    }

    private static void assertMetadataManager(Object metadata, JpaTransactionManager expected) {
        var interceptor = Arrays.stream(((Advised) metadata).getAdvisors())
                .map(advisor -> advisor.getAdvice()).filter(TransactionInterceptor.class::isInstance)
                .map(TransactionInterceptor.class::cast).findFirst().orElseThrow();
        assertThat(interceptor.getTransactionManager()).isSameAs(expected);
    }

    @Configuration(proxyBeanMethods = false)
    static class WriterFixture {
        @Bean HikariDataSource writeDataSource() {
            var source = mock(HikariDataSource.class);
            when(source.getMaximumPoolSize()).thenReturn(3);
            return source;
        }
        @Bean JdbcTemplate writeJdbc(HikariDataSource source) { return new JdbcTemplate(source); }
        @Bean JdbcTransactionManager writeTransactionManager(HikariDataSource source) { return new JdbcTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
