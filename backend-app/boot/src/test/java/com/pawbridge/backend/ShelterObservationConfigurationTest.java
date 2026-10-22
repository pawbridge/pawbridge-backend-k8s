package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.boot.configuration.ShelterObservationConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ShelterObservationConfigurationTest {
    private static final String ENABLED = "pawbridge.shelter-observations.schedule-enabled=true";
    private static final String BACKEND = "pawbridge.animal-query.backend=postgresql";

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "yes", "on"})
    void given__missing_or_non_true_schedule_flag__when__assembling_postgresql_web_context__then__create_no_producer_or_scheduler(String flag) {
        var runner = new WebApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration.class)
                .withPropertyValues(BACKEND);
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.shelter-observations.schedule-enabled=" + flag);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JdbcShelterObservationRecorder.class)
                .doesNotHaveBean(ShelterObservationConfiguration.ShelterObservationJob.class)
                .doesNotHaveBean(ThreadPoolTaskScheduler.class).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "elasticsearch"})
    void given__missing_or_other_query_backend__when__enabling_web_schedule__then__create_no_producer_or_scheduler(String backend) {
        var runner = new WebApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration.class)
                .withPropertyValues(ENABLED);
        if (!backend.equals("absent")) runner = runner.withPropertyValues("pawbridge.animal-query.backend=" + backend);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JdbcShelterObservationRecorder.class)
                .doesNotHaveBean(ThreadPoolTaskScheduler.class).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void given__a_non_web_process__when__both_flags_match__then__create_no_producer_or_scheduler() {
        new ApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration.class)
                .withPropertyValues(ENABLED, BACKEND).run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(JdbcShelterObservationRecorder.class).doesNotHaveBean(ThreadPoolTaskScheduler.class)
                        .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void given__actual_main_and_explicit_observation_configuration__when__assembling_then_closing__then__reuse_writer_and_named_cron_without_startup_sql(CapturedOutput output) throws Exception {
        var values = F3TestInputs.values();
        values.put("F2_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/pawbridge_observation_synthetic");
        var read = ReadDatabaseSettings.fromEnvironment(values::get);
        var write = PostWriteSettings.fromEnvironment(values::get);
        // Exercise the actual cron property consumer, several days away from execution in every test run.
        var futureDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3);
        var futureCron = "0 0 4 " + futureDate.getDayOfMonth() + " " + futureDate.getMonthValue() + " *";
        var schedulerRef = new AtomicReference<ThreadPoolTaskScheduler>();
        var delayedRef = new AtomicReference<ScheduledFuture<?>>();
        var poolsRef = new AtomicReference<List<HikariDataSource>>();
        new WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication.class, ShelterObservationConfiguration.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("readDatabaseSettings", read);
                    context.getBeanFactory().registerSingleton("postWriteSettings", write);
                }).withPropertyValues(ENABLED, BACKEND, "pawbridge.shelter-observations.cron=" + futureCron,
                        "spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.chat.enabled=false",
                        "pawbridge.jobs.cleanup-enabled=false", "pawbridge.user-maintenance.enabled=false",
                        "pawbridge.community-maintenance.enabled=false",
                        "pawbridge.auth.google-enabled=false", "pawbridge.auth.sessions-enabled=false",
                        "pawbridge.visits.enabled=false", "pawbridge.visits.collection-enabled=false",
                        "pawbridge.animal-query.projection-schedule-enabled=false", "apms.batch.schedule-enabled=false",
                        "apms-photo-archive.enabled=false", "tourapi.enabled=false", "tourapi.schedule-enabled=false",
                        "lost-gallery-feed.enabled=false", "pawbridge.animal-lost-search.enabled=false",
                        "pawbridge.home-videos.management-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcShelterObservationRecorder.class)
                            .hasSingleBean(ShelterObservationConfiguration.ShelterObservationJob.class)
                            .hasSingleBean(ThreadPoolTaskScheduler.class);
                    var recorder = context.getBean(JdbcShelterObservationRecorder.class);
                    var jdbc = (JdbcTemplate) ReflectionTestUtils.getField(recorder, "jdbc");
                    var transaction = (TransactionTemplate) ReflectionTestUtils.getField(recorder, "transaction");
                    assertThat(jdbc.getDataSource()).isSameAs(context.getBean("writeDataSource"));
                    assertThat(jdbc.getQueryTimeout()).isEqualTo(30);
                    assertThat(transaction.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
                    assertThat(transaction).isNotSameAs(context.getBean("writeTransaction"));
                    assertThat(transaction.getTimeout()).isEqualTo(30);
                    assertThat(transaction.isReadOnly()).isFalse();
                    assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                    assertThat(ReflectionTestUtils.getField(context.getBean(ShelterObservationConfiguration.ShelterObservationJob.class), "recorder"))
                            .isSameAs(recorder);
                    assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys("dataSource");
                    assertThat(context.getBean("writeDataSource")).isSameAs(context.getBean("dataSource"));
                    assertThat(context.getBeansOfType(JdbcTransactionManager.class)).containsOnlyKeys("transactionManager", "writeTransactionManager");
                    assertThat(context.getBeansOfType(jakarta.persistence.EntityManagerFactory.class)).isEmpty();
                    poolsRef.set(List.copyOf(context.getBeansOfType(HikariDataSource.class).values()));
                    poolsRef.get().forEach(pool -> {
                        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
                        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    });
                    var scheduler = context.getBean("shelterObservationScheduler", ThreadPoolTaskScheduler.class);
                    schedulerRef.set(scheduler);
                    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("shelter-observation-");
                    assertThat(scheduler.submit(() -> Thread.currentThread().isDaemon()).get(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
                    assertThat(ReflectionTestUtils.getField(scheduler, "acceptTasksAfterContextClose")).isEqualTo(false);
                    assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
                    assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).isEqualTo(35000L);
                    assertThat(context.getBeanFactory().getDependenciesForBean("shelterObservationScheduler"))
                            .contains("dataSource", "shelterObservationRecorder");
                    var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    assertThat(tasks.iterator().next().getTask()).isInstanceOf(CronTask.class);
                    assertThat(((CronTask) tasks.iterator().next().getTask()).getExpression()).isEqualTo(futureCron);
                    var metadata = ShelterObservationConfiguration.ShelterObservationJob.class.getMethod("record").getAnnotation(Scheduled.class);
                    assertThat(metadata.cron()).isEqualTo("${pawbridge.shelter-observations.cron:0 0 4 * * *}");
                    assertThat(metadata.zone()).isEqualTo("Asia/Seoul");
                    assertThat(metadata.scheduler()).isEqualTo("shelterObservationScheduler");
                    var errors = (ErrorHandler) ReflectionTestUtils.getField(scheduler, "errorHandler");
                    errors.handleError(new IllegalStateException("synthetic-private-observation-SQL", new RuntimeException("synthetic-private-cause")));
                    assertThat(output.getAll()).contains("Shelter daily observation failed (IllegalStateException)")
                            .doesNotContain("synthetic-private-observation-SQL", "synthetic-private-cause");
                    delayedRef.set(scheduler.scheduleWithFixedDelay(() -> { throw new AssertionError("Delayed probe must be cancelled"); },
                            java.time.Instant.now().plusSeconds(60), Duration.ofSeconds(60)));
                    assertThat(output.getAll()).doesNotContain("Shelter daily observations recorded:");
                });
        assertThat(delayedRef.get().isCancelled()).isTrue();
        assertThat(schedulerRef.get().getScheduledThreadPoolExecutor().isTerminated()).isTrue();
        assertThat(poolsRef.get()).hasSize(1).allSatisfy(pool -> assertThat(pool.isClosed()).isTrue());
    }

    @Test
    void given__another_writer_transaction_manager__when__enabling_observations__then__reject_before_borrowing() {
        var source = mock(DataSource.class);
        var other = mock(DataSource.class);
        new WebApplicationContextRunner().withUserConfiguration(ShelterObservationConfiguration.class)
                .withBean("writeDataSource", DataSource.class, () -> source)
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(other))
                .withPropertyValues(ENABLED, BACKEND).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Shelter observations require the shared writer transaction manager");
                    verifyNoInteractions(source, other);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__a_successful_or_failed_recorder__when__scheduled_adapter_runs__then__call_once_and_preserve_failure(boolean fails) {
        var recorder = mock(JdbcShelterObservationRecorder.class);
        var failure = new IllegalStateException("synthetic-observation-unavailable");
        if (fails) when(recorder.recordToday()).thenThrow(failure);
        else when(recorder.recordToday()).thenReturn(3);
        var job = new ShelterObservationConfiguration.ShelterObservationJob(recorder);
        if (fails) assertThatThrownBy(job::record).isSameAs(failure);
        else job.record();
        verify(recorder).recordToday();
        verifyNoMoreInteractions(recorder);
    }
}
