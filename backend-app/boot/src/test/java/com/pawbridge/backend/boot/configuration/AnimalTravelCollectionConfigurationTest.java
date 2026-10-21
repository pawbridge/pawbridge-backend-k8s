package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PetTravelCollector;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiClient;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.incrementer.PostgresSequenceMaxValueIncrementer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnimalTravelCollectionConfigurationTest {
    @Test
    void givenExistingGalleryAndShelterAssembly__whenTravelCollectionIsAdded__thenMainStillRegistersAllThreeFeatures() {
        var imports = com.pawbridge.backend.PawBridgeApplication.class
                .getAnnotation(org.springframework.context.annotation.Import.class).value();
        assertThat(imports).contains(AnimalGalleryFeedConfiguration.class,
                ShelterCommandConfiguration.class, AnimalTravelCollectionConfiguration.class);
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

    private ApplicationContextRunner runner(HikariDataSource source, JdbcTemplate jdbc, JdbcTransactionManager manager) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(AnimalTravelCollectionConfiguration.class)
                .withBean("writeDataSource", HikariDataSource.class, () -> source)
                .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> manager)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("readClock", Clock.class, () -> CLOCK);
    }

    private HikariDataSource source(int capacity) {
        var source = mock(HikariDataSource.class);
        when(source.getMaximumPoolSize()).thenReturn(capacity);
        return source;
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "query-only", "schedule-only"})
    void givenCollectionDisabledOrOnlyQueryOrScheduleSelected__whenContextStarts__thenNoCollectionComponentsOrBorrow(String selection) {
        var source = source(1);
        var context = runner(source, new JdbcTemplate(source), new JdbcTransactionManager(source));
        if (selection.equals("false")) context = context.withPropertyValues("tourapi.enabled=false");
        if (selection.equals("query-only")) context = context.withPropertyValues(
                "pawbridge.animal-travel.enabled=true", "pawbridge.animal-query.backend=postgresql");
        if (selection.equals("schedule-only")) context = context.withPropertyValues("tourapi.schedule-enabled=true");
        context.run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(TourApiClient.class)
                    .doesNotHaveBean(TourApiProperties.class).doesNotHaveBean(PostgresqlTravelCollectionCatalog.class)
                    .doesNotHaveBean(PetTravelCollector.class).doesNotHaveBean(JobRepository.class)
                    .doesNotHaveBean(Job.class).doesNotHaveBean(AnimalTravelCollectionSchedule.class)
                    .doesNotHaveBean(ThreadPoolTaskScheduler.class);
            verify(source, never()).getConnection();
        });
    }

    @Test
    void givenCollectionEnabledWithoutSchedule__whenContextStarts__thenExplicitPostgresqlBatchUsesSameWriterWithoutMetadataOrJobExecution() {
        var source = source(2);
        var jdbc = new JdbcTemplate(source);
        var manager = new JdbcTransactionManager(source);
        runner(source, jdbc, manager).withPropertyValues("tourapi.enabled=true", "tourapi.schedule-enabled=false")
                .run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(TourApiClient.class)
                            .hasSingleBean(PostgresqlTravelCollectionCatalog.class).hasSingleBean(PetTravelCollector.class)
                            .hasSingleBean(JobRepository.class).hasSingleBean(Job.class)
                            .doesNotHaveBean(JobLauncher.class).doesNotHaveBean("jobLauncherApplicationRunner")
                            .doesNotHaveBean(ThreadPoolTaskScheduler.class);
                    assertThat(app.getBean(TourApiProperties.class).isEnabled()).isTrue();
                    var catalog = app.getBean(PostgresqlTravelCollectionCatalog.class);
                    assertThat(ReflectionTestUtils.getField(catalog, "jdbc")).isSameAs(jdbc);
                    var catalogWrites = (TransactionTemplate) ReflectionTestUtils.getField(catalog, "transaction");
                    assertThat(catalogWrites.getTransactionManager()).isSameAs(manager);
                    Object collector = app.getBean(PetTravelCollector.class);
                    assertThat(ReflectionTestUtils.getField(collector, "dataSource")).isSameAs(source);
                    assertThat(ReflectionTestUtils.getField(collector, "clock")).isSameAs(CLOCK);

                    var repository = app.getBean(JobRepository.class);
                    Object target = AopTestUtils.getUltimateTargetObject(repository);
                    for (String field : new String[]{"jobInstanceDao", "jobExecutionDao", "stepExecutionDao", "ecDao"}) {
                        Object dao = ReflectionTestUtils.getField(target, field);
                        assertThat(ReflectionTestUtils.getField(dao, "tablePrefix")).isEqualTo("pawbridge_animal.BATCH_");
                        var metadataJdbc = (JdbcTemplate) ReflectionTestUtils.getField(dao, "jdbcTemplate");
                        assertThat(metadataJdbc.getDataSource()).isSameAs(source);
                    }
                    Object instances = ReflectionTestUtils.getField(target, "jobInstanceDao");
                    Object incrementer = ReflectionTestUtils.getField(instances, "jobInstanceIncrementer");
                    assertThat(incrementer).isInstanceOf(PostgresSequenceMaxValueIncrementer.class);
                    assertThat(((PostgresSequenceMaxValueIncrementer) incrementer).getIncrementerName())
                            .isEqualTo("pawbridge_animal.BATCH_JOB_SEQ");
                    var interceptor = Arrays.stream(((Advised) repository).getAdvisors())
                            .map(advisor -> advisor.getAdvice()).filter(TransactionInterceptor.class::isInstance)
                            .map(TransactionInterceptor.class::cast).findFirst().orElseThrow();
                    assertThat(interceptor.getTransactionManager()).isSameAs(manager);

                    var job = (SimpleJob) app.getBean(Job.class);
                    assertThat(job.getName()).isEqualTo("petTravelCollectionJob");
                    assertThat(job.getStepNames()).containsExactly("petTravelCollectionStep");
                    var step = (TaskletStep) job.getStep("petTravelCollectionStep");
                    assertThat(ReflectionTestUtils.getField(step, "transactionManager")).isSameAs(manager);
                    var attribute = (TransactionAttribute) ReflectionTestUtils.getField(step, "transactionAttribute");
                    assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                    verify(source, never()).getConnection();
                });
    }

    @Test
    void givenOnlyOneWriterConnection__whenCollectionEnabled__thenFailBeforeMetadataOrBorrow() {
        var source = source(1);
        runner(source, new JdbcTemplate(source), new JdbcTransactionManager(source))
                .withPropertyValues("tourapi.enabled=true").run(app -> {
                    assertThat(app).hasFailed();
                    assertThat(app.getStartupFailure()).hasRootCauseMessage(
                            "Travel collection requires the same writer with capacity of at least two");
                    verify(source, never()).getConnection();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "transaction-manager"})
    void givenAnotherWriterDataSource__whenCollectionEnabled__thenRejectMixedOwnershipBeforeBorrow(String mismatch) {
        var source = source(2);
        var other = source(2);
        var jdbc = new JdbcTemplate(mismatch.equals("jdbc") ? other : source);
        var manager = new JdbcTransactionManager(mismatch.equals("transaction-manager") ? other : source);
        runner(source, jdbc, manager).withPropertyValues("tourapi.enabled=true").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage(
                    "Travel collection requires the same writer with capacity of at least two");
            verify(source, never()).getConnection();
            verify(other, never()).getConnection();
        });
    }

    @Test
    void givenCollectionAndScheduleEnabled__whenContextStarts__thenOneNamedSeoulSchedulerWaitsOnNormalShutdownWithoutLaunchingJob() {
        var source = source(2);
        runner(source, new JdbcTemplate(source), new JdbcTransactionManager(source))
                // A future cron prevents this composition test from invoking the actual Job.
                .withPropertyValues("tourapi.enabled=true", "tourapi.schedule-enabled=true", "tourapi.collection-cron=0 0 0 1 1 *")
                .run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(AnimalTravelCollectionSchedule.class)
                            .hasSingleBean(ThreadPoolTaskScheduler.class);
                    var scheduler = app.getBean("tourApiCollectionScheduler", ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
                    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("pawbridge-tourapi-");
                    assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
                    assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis"))
                            .isEqualTo((long) Integer.MAX_VALUE * 1000);
                    var scheduled = AnimalTravelCollectionSchedule.class.getDeclaredMethod("collect").getAnnotation(Scheduled.class);
                    assertThat(scheduled.scheduler()).isEqualTo("tourApiCollectionScheduler");
                    assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
                    assertThat(scheduled.cron()).isEqualTo("${tourapi.collection-cron:0 */30 * * * *}");
                    var dependencies = AnimalTravelCollectionSchedule.class.getDeclaredMethod("tourApiCollectionScheduler")
                            .getAnnotation(DependsOn.class);
                    assertThat(dependencies.value()).containsExactlyInAnyOrder("writeDataSource", "petTravelCollectionJob");
                    verify(source, never()).getConnection();
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void givenDefaultOrExplicitWriterCapacity__whenCreatingSharedWriter__thenRetainLazyEmptyPoolAndRequestedCapacity(int capacity) throws Exception {
        var values = settings();
        var reader = ReadDatabaseSettings.fromEnvironment(values::get);
        var writer = PostWriteSettings.fromEnvironment(values::get, reader);
        var method = PostWriteConfiguration.class.getDeclaredMethod("writeDataSource",
                ReadDatabaseSettings.class, PostWriteSettings.class, int.class);
        assertThat(method.getParameters()[2].getAnnotation(Value.class).value())
                .isEqualTo("${pawbridge.database.writer.maximum-pool-size:1}");
        try (var source = new PostWriteConfiguration().writeDataSource(reader, writer, capacity)) {
            assertThat(source.getMaximumPoolSize()).isEqualTo(capacity);
            assertThat(source.getMinimumIdle()).isZero();
            assertThat(source.getInitializationFailTimeout()).isNegative();
            assertThat(source.getHikariPoolMXBean().getTotalConnections()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void givenNonPositiveWriterCapacity__whenCreatingSharedWriter__thenRejectBeforePoolConstruction(int capacity) {
        var values = settings();
        var reader = ReadDatabaseSettings.fromEnvironment(values::get);
        var writer = PostWriteSettings.fromEnvironment(values::get, reader);
        assertThatIllegalArgumentException().isThrownBy(
                () -> new PostWriteConfiguration().writeDataSource(reader, writer, capacity))
                .withMessage("Writer pool capacity must be positive");
    }

    private Map<String, String> settings() {
        return Map.ofEntries(Map.entry("F2_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/pb_never_connected"),
                Map.entry("F2_DB_USER", "synthetic_reader"), Map.entry("F2_DB_PASSWORD", "synthetic"),
                Map.entry("F3_DB_WRITER_USER", "synthetic_writer"), Map.entry("F3_DB_WRITER_PASSWORD", "synthetic"),
                Map.entry("F3_JWT_SECRET", "synthetic-test-key-with-thirty-two-characters"),
                Map.entry("F3_STORAGE_ENDPOINT", "http://127.0.0.1:1"), Map.entry("F3_STORAGE_BUCKET", "synthetic-bucket"),
                Map.entry("F3_STORAGE_PUBLIC_BASE_URL", "https://storage.invalid"), Map.entry("F3_STORAGE_OBJECT_PREFIX", "synthetic"),
                Map.entry("F3_STORAGE_ACCESS_KEY_ID", "synthetic"), Map.entry("F3_STORAGE_SECRET_ACCESS_KEY", "synthetic"),
                Map.entry("F3_STORAGE_REGION", "synthetic-region"));
    }
}
