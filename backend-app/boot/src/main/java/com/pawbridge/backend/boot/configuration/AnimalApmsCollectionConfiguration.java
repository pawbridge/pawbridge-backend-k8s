package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchExecutionRecovery;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchProperties;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlanFactory;
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController;
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.DefaultExecutionContextSerializer;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.scope.JobScope;
import org.springframework.batch.core.scope.StepScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * APMS-only composition; the existing Animal writer remains the sole EMF and database pool owner.
 * Assembled for the schedule or the original manual API; each entry point keeps its own flag.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AnimalApmsCollectionConfiguration.ApmsEntryPoint.class)
@EnableConfigurationProperties
@Import({AnimalApmsJobConfiguration.class, AnimalJpaWriterConfiguration.class})
public class AnimalApmsCollectionConfiguration {
    @Bean
    ApmsScheduleSettings apmsScheduleSettings(Environment environment,
            @Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager animalManager,
            @Qualifier("animalCommandSearch") SearchDocumentWriter search) {
        var settings = new ApmsScheduleSettings(environment);
        settings.requireCapacity(source.getMaximumPoolSize());
        if (jdbc.getDataSource() != source || animalManager.getDataSource() != source || search.dataSource() != source)
            throw new IllegalArgumentException("APMS must use the same existing writer DataSource");
        return settings;
    }

    @Bean
    @ConfigurationProperties(prefix = "apms.batch")
    ApmsBatchProperties apmsBatchProperties() { return new ApmsBatchProperties(); }

    @Bean
    Clock apmsClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }

    @Bean
    Clock apmsMetadataClock() { return Clock.systemDefaultZone(); }

    @Bean(destroyMethod = "close")
    ApmsApiClient apmsApiClient(ApmsScheduleSettings settings, ObjectMapper mapper) {
        return new ApmsHttpClient(settings.baseUri(), mapper);
    }

    @Bean
    static JobScope apmsJobScope() {
        var scope = new JobScope();
        scope.setAutoProxy(false);
        return scope;
    }

    @Bean
    static StepScope apmsStepScope() {
        var scope = new StepScope();
        scope.setAutoProxy(false);
        return scope;
    }

    @Bean
    DefaultExecutionContextSerializer apmsExecutionContextSerializer() {
        return new DefaultExecutionContextSerializer();
    }

    @Bean
    JobRepository apmsJobRepository(@Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            @Qualifier("apmsExecutionContextSerializer") DefaultExecutionContextSerializer apmsExecutionContextSerializer,
            ApmsScheduleSettings requiredSettings) throws Exception {
        var factory = new JobRepositoryFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionManager(manager);
        factory.setDatabaseType("POSTGRES");
        factory.setTablePrefix("pawbridge_animal.BATCH_");
        factory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE");
        factory.setSerializer(apmsExecutionContextSerializer);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    JobExplorer apmsJobExplorer(@Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            @Qualifier("apmsExecutionContextSerializer") DefaultExecutionContextSerializer apmsExecutionContextSerializer,
            ApmsScheduleSettings requiredSettings) throws Exception {
        var factory = new JobExplorerFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionManager(manager);
        factory.setTablePrefix("pawbridge_animal.BATCH_");
        factory.setSerializer(apmsExecutionContextSerializer);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    TaskExecutorJobLauncher apmsJobLauncher(@Qualifier("apmsJobRepository") JobRepository repository) throws Exception {
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(new SyncTaskExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }

    @Bean
    ApmsBatchExecutionRecovery apmsBatchExecutionRecovery(@Qualifier("apmsJobExplorer") JobExplorer explorer,
            @Qualifier("apmsJobRepository") JobRepository repository, @Qualifier("apmsMetadataClock") Clock clock,
            ApmsBatchProperties properties, @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            ApmsScheduleSettings settings) {
        return new ApmsBatchExecutionRecovery(explorer, repository, clock, properties, manager, settings.transactionTimeout());
    }

    @Bean
    ApmsSyncPlanFactory apmsSyncPlanFactory(@Qualifier("animalCommandAnimals") AnimalRepository animals,
            @Qualifier("apmsJobExplorer") JobExplorer explorer, @Qualifier("apmsClock") Clock clock) {
        return new ApmsSyncPlanFactory(animals, explorer, clock);
    }

    @Bean
    ApmsBatchRunner apmsBatchRunner(@Qualifier("writeDataSource") HikariDataSource source,
            ApmsBatchExecutionRecovery recovery, @Qualifier("apmsJobLauncher") TaskExecutorJobLauncher launcher,
            @Qualifier("apmsAnimalSyncJob") Job job, ApmsSyncPlanFactory plans) {
        return new ApmsBatchRunner(source, recovery, launcher, job, plans);
    }

    @Bean
    ApmsChunkExecutor apmsBatchTaskExecutor(ApmsScheduleSettings settings) {
        var executor = new ApmsChunkExecutor();
        executor.setCorePoolSize(settings.workers());
        executor.setMaxPoolSize(settings.workers());
        executor.setQueueCapacity(4);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("apms-chunk-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean
    @DependsOn("apmsApiClient")
    @ConditionalOnProperty(prefix = "apms.batch", name = "schedule-enabled", havingValue = "true")
    ApmsCollectionSchedule apmsCollectionSchedule(ApmsBatchRunner runner, ApmsScheduleSettings settings,
            @Qualifier("apmsBatchTaskExecutor") ApmsChunkExecutor requiredExecutor) {
        return new ApmsCollectionSchedule(runner, settings.cron());
    }

    @Bean
    @DependsOn("apmsApiClient")
    @ConditionalOnProperty(prefix = "pawbridge.apms-manual", name = "enabled", havingValue = "true")
    ApmsBatchController apmsBatchController(ApmsBatchRunner runner,
            @Qualifier("apmsBatchTaskExecutor") ApmsChunkExecutor requiredExecutor) {
        return new ApmsBatchController(runner);
    }

    /** Either explicit entry point assembles the shared APMS job; neither is on by default. */
    static final class ApmsEntryPoint extends AnyNestedCondition {
        ApmsEntryPoint() { super(ConfigurationPhase.PARSE_CONFIGURATION); }

        @ConditionalOnProperty(prefix = "apms.batch", name = "schedule-enabled", havingValue = "true")
        static final class Schedule {}

        @ConditionalOnProperty(prefix = "pawbridge.apms-manual", name = "enabled", havingValue = "true")
        static final class ManualApi {}
    }

    /** Wait for submitted chunks without a timeout that would close the writer under live work. */
    static final class ApmsChunkExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void destroy() {
            shutdown();
            boolean interrupted = false;
            try {
                while (!getThreadPoolExecutor().isTerminated()) {
                    try { getThreadPoolExecutor().awaitTermination(1, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
