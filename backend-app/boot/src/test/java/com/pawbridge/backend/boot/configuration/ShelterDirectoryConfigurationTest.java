package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryClient;
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryCollector;
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShelterDirectoryConfigurationTest {
    @Test
    void givenMainImports__whenShelterDirectoryIsAdded__thenMainRegistersTheExplicitConfiguration() {
        var imports = com.pawbridge.backend.PawBridgeApplication.class
                .getAnnotation(org.springframework.context.annotation.Import.class).value();
        assertThat(imports).contains(ShelterDirectoryConfiguration.class, AnimalTravelCollectionConfiguration.class);
    }

    private ApplicationContextRunner runner(HikariDataSource source, JdbcTransactionManager manager) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(ShelterDirectoryConfiguration.class)
                .withBean("writeDataSource", HikariDataSource.class, () -> source)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(source))
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> manager)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "on", "key-only"})
    void givenScheduleSwitchNotTrue__whenContextStarts__thenNoClientJobSchedulerAnalyzerOrBorrow(String selection) {
        var source = mock(HikariDataSource.class);
        var context = runner(source, new JdbcTransactionManager(source));
        if (selection.equals("false")) context = context.withPropertyValues("shelter-directory.schedule-enabled=false");
        if (selection.equals("on")) context = context.withPropertyValues("shelter-directory.schedule-enabled=on");
        if (selection.equals("key-only")) context = context.withPropertyValues("shelter-directory.service-key=test-key");
        context.run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(ShelterDirectoryClient.class)
                    .doesNotHaveBean(ShelterDirectoryStore.class).doesNotHaveBean(ShelterDirectoryCollector.class)
                    .doesNotHaveBean(JobRepository.class).doesNotHaveBean(Job.class)
                    .doesNotHaveBean(ShelterDirectorySchedule.class).doesNotHaveBean(ThreadPoolTaskScheduler.class)
                    .doesNotHaveBean(KoreanSearchAnalyzer.class);
            verify(source, never()).getConnection();
        });
    }

    @Test
    void givenScheduleSwitchTrue__whenContextStarts__thenOriginalJobUsesSameWriterAndOneSeoulSchedulerWithoutLaunching() {
        var source = mock(HikariDataSource.class);
        when(source.getMaximumPoolSize()).thenReturn(2);
        var manager = new JdbcTransactionManager(source);
        // A future cron prevents this composition test from invoking the actual Job.
        runner(source, manager).withPropertyValues("shelter-directory.schedule-enabled=true",
                        "shelter-directory.collection-cron=0 0 0 1 1 *")
                .run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(ShelterDirectoryClient.class)
                            .hasSingleBean(ShelterDirectoryStore.class).hasSingleBean(ShelterDirectoryCollector.class)
                            .hasSingleBean(JobRepository.class).hasSingleBean(Job.class)
                            .hasSingleBean(ShelterDirectorySchedule.class).hasSingleBean(ThreadPoolTaskScheduler.class)
                            .hasSingleBean(KoreanSearchAnalyzer.class);
                    assertThat(ReflectionTestUtils.getField(app.getBean(ShelterDirectoryClient.class), "key")).isEqualTo("");
                    assertThat(ReflectionTestUtils.getField(app.getBean(ShelterDirectoryCollector.class), "source")).isSameAs(source);
                    Object search = ReflectionTestUtils.getField(app.getBean(ShelterDirectoryStore.class), "searchDocuments");
                    assertThat(ReflectionTestUtils.getField(search, "analyzer")).isSameAs(app.getBean(KoreanSearchAnalyzer.class));

                    Object target = AopTestUtils.getUltimateTargetObject(app.getBean(JobRepository.class));
                    Object dao = ReflectionTestUtils.getField(target, "jobExecutionDao");
                    assertThat(ReflectionTestUtils.getField(dao, "tablePrefix")).isEqualTo("pawbridge_animal.BATCH_");
                    assertThat(((JdbcTemplate) ReflectionTestUtils.getField(dao, "jdbcTemplate")).getDataSource()).isSameAs(source);

                    var job = (SimpleJob) app.getBean(Job.class);
                    assertThat(job.getName()).isEqualTo("shelterDirectoryJob");
                    assertThat(job.getStepNames()).containsExactly("shelterDirectoryStep");
                    var step = (TaskletStep) job.getStep("shelterDirectoryStep");
                    assertThat(ReflectionTestUtils.getField(step, "transactionManager")).isSameAs(manager);
                    var attribute = (TransactionAttribute) ReflectionTestUtils.getField(step, "transactionAttribute");
                    assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

                    var scheduler = app.getBean("shelterDirectoryScheduler", ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("pawbridge-shelter-directory-");
                    assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
                    verify(source, never()).getConnection();
                });
    }

    @Test
    void givenAnotherWriterDataSource__whenScheduleEnabled__thenRejectMixedOwnershipBeforeBorrow() {
        var source = mock(HikariDataSource.class);
        when(source.getMaximumPoolSize()).thenReturn(2);
        var other = mock(HikariDataSource.class);
        runner(source, new JdbcTransactionManager(other)).withPropertyValues("shelter-directory.schedule-enabled=true")
                .run(app -> {
                    assertThat(app).hasFailed();
                    assertThat(app.getStartupFailure()).hasRootCauseMessage(
                            "Shelter directory must use the same existing writer DataSource");
                    verify(source, never()).getConnection();
                    verify(other, never()).getConnection();
                });
    }

    @Test
    void givenDefaultSingleWriterConnection__whenScheduleEnabled__thenFailBeforeMetadataOrBorrow() {
        var source = mock(HikariDataSource.class);
        when(source.getMaximumPoolSize()).thenReturn(1);
        runner(source, new JdbcTransactionManager(source)).withPropertyValues("shelter-directory.schedule-enabled=true")
                .run(app -> {
                    assertThat(app).hasFailed();
                    assertThat(app.getStartupFailure()).hasRootCauseMessage(
                            "Shelter directory requires the writer with capacity of at least two");
                    verify(source, never()).getConnection();
                });
    }

    @Test
    void givenScheduleMethod__whenReadingContract__thenKeepsOriginalCronZoneOnNamedScheduler() throws Exception {
        var scheduled = ShelterDirectorySchedule.class.getDeclaredMethod("collect").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${shelter-directory.collection-cron:0 0 4 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        assertThat(scheduled.scheduler()).isEqualTo("shelterDirectoryScheduler");
        var dependencies = ShelterDirectorySchedule.class.getDeclaredMethod("shelterDirectoryScheduler").getAnnotation(DependsOn.class);
        assertThat(dependencies.value()).containsExactlyInAnyOrder("writeDataSource", "shelterDirectoryJob");
    }
}
