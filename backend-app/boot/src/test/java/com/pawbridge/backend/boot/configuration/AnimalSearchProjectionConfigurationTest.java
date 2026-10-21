package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.PawBridgeApplication;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalSearchProjectionConfigurationTest {
    private static final String ENABLED = "pawbridge.animal-query.projection-schedule-enabled=true";
    private static final String BACKEND = "pawbridge.animal-query.backend=postgresql";
    private static final String ANALYZED = "pawbridge.animal-query.analyzed-text=true";

    @ParameterizedTest
    @ValueSource(strings = {"missing", "false", "on", "yes", "backend", "analyzed"})
    void missing_or_nonliteral_inputs_do_not_create_a_job_or_borrow_a_connection(String missing) {
        DataSource source = mock(DataSource.class);
        WebApplicationContextRunner runner = runner(source);
        if (missing.equals("backend")) runner = runner.withPropertyValues(ENABLED, ANALYZED,
                "pawbridge.animal-query.backend=elasticsearch");
        else if (missing.equals("analyzed")) runner = runner.withPropertyValues(ENABLED, BACKEND,
                "pawbridge.animal-query.analyzed-text=false");
        else if (missing.equals("missing")) runner = runner.withPropertyValues(BACKEND, ANALYZED);
        else runner = runner.withPropertyValues(BACKEND, ANALYZED,
                "pawbridge.animal-query.projection-schedule-enabled=" + missing);
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PostgresqlSearchProjector.class)
                    .doesNotHaveBean(AnimalSearchProjectionJobs.class)
                    .doesNotHaveBean("animalSearchProjectionScheduler");
            verifyNoInteractions(source);
        });
    }

    @Test
    void non_web_process_does_not_schedule_projection_work() {
        new ApplicationContextRunner().withUserConfiguration(AnimalSearchProjectionConfiguration.class)
                .withPropertyValues(ENABLED, BACKEND, ANALYZED)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(PostgresqlSearchProjector.class)
                        .doesNotHaveBean(AnimalSearchProjectionJobs.class));
    }

    @Test
    void enabled_servlet_context_uses_existing_resources_and_one_named_scheduler() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("owned-test-unavailable"));
        runner(source).withPropertyValues(ENABLED, BACKEND, ANALYZED).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PostgresqlSearchProjector.class)
                    .hasSingleBean(AnimalSearchProjectionJobs.class).hasSingleBean(KoreanSearchAnalyzer.class);
            assertThat(context.getBean(KoreanSearchAnalyzer.class))
                    .isSameAs(context.getBean("animalDiscoveryAnalyzer"));
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(JdbcTransactionManager.class)).hasSize(1);
            var scheduler = context.getBean("animalSearchProjectionScheduler",
                    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("animal-search-projection-");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        });
    }

    @Test
    void existing_feature_and_projection_imports_share_one_real_analyzer() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("owned-test-unavailable"));
        runner(source).withUserConfiguration(AnimalSearchConfiguration.class)
                .withPropertyValues(ENABLED, BACKEND, ANALYZED, "pawbridge.animal-commands.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KoreanSearchAnalyzer.class)
                            .hasSingleBean(PostgresqlSearchProjector.class);
                    assertThat(context.getBean(KoreanSearchAnalyzer.class))
                            .isSameAs(context.getBean("animalDiscoveryAnalyzer"));
                });
    }

    @Test
    void other_writer_manager_is_rejected_before_a_connection_is_borrowed() {
        DataSource source = mock(DataSource.class);
        DataSource other = mock(DataSource.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new AnimalSearchProjectionConfiguration()
                .animalSearchProjector(source, new JdbcTransactionManager(other), mock(KoreanSearchAnalyzer.class)));
        verifyNoInteractions(source, other);
    }

    @Test
    void scheduler_worker_is_daemon_and_shutdown_cancels_delayed_work() throws Exception {
        var scheduler = new AnimalSearchProjectionConfiguration().animalSearchProjectionScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.submit(() -> Thread.currentThread().isDaemon()).get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            var delayed = scheduler.scheduleWithFixedDelay(() -> { throw new AssertionError("must not start"); },
                    java.time.Instant.now().plusSeconds(60), Duration.ofSeconds(60));
            scheduler.shutdown();
            assertThat(delayed.isCancelled()).isTrue();
            assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
        } finally { scheduler.shutdown(); }
    }

    @Test
    void main_keeps_existing_archive_and_collection_imports_when_adding_maintenance() {
        assertThat(PawBridgeApplication.class.getAnnotation(Import.class).value())
                .contains(AnimalSearchProjectionConfiguration.class, AnimalPhotoArchiveConfiguration.class,
                        AnimalApmsCollectionConfiguration.class, AnimalTravelCollectionConfiguration.class,
                        AnimalGalleryFeedConfiguration.class, ShelterCommandConfiguration.class);
    }

    private WebApplicationContextRunner runner(DataSource source) {
        return new WebApplicationContextRunner().withUserConfiguration(AnimalSearchProjectionConfiguration.class)
                .withBean("writeDataSource", DataSource.class, () -> source)
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(source));
    }
}
