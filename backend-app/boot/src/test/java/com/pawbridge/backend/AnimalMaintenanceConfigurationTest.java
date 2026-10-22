package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.AnimalMaintenanceConfiguration;
import com.pawbridge.backend.animal.internal.maintenance.infrastructure.JdbcAnimalOutboxRetention;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AnimalMaintenanceConfigurationTest {
    @ParameterizedTest
    @CsvSource({"missing,missing", "true,missing", "missing,true", "false,true", "true,false", "false,false"})
    void given__either_gate_closed_or_missing__when__starting_without_writer__then__create_no_maintenance_or_scheduling(String outer, String cleanup) {
        var runner = new ApplicationContextRunner().withUserConfiguration(AnimalMaintenanceConfiguration.class);
        if (!outer.equals("missing")) runner = runner.withPropertyValues("pawbridge.animal-maintenance.enabled=" + outer);
        if (!cleanup.equals("missing")) runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=" + cleanup);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(JdbcAnimalOutboxRetention.class)).isEmpty();
            assertThat(context.getBeansOfType(AnimalMaintenanceConfiguration.OutboxRetentionJobs.class)).isEmpty();
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class OtherSchedulingConfiguration { }

    @ParameterizedTest
    @CsvSource({"missing,missing", "true,missing", "missing,true", "false,true", "true,false", "false,false"})
    void given__another_feature_enables_scheduling__when__either_animal_gate_is_closed__then__do_not_register_animal_workers(String outer, String cleanup) {
        var runner = new ApplicationContextRunner().withUserConfiguration(AnimalMaintenanceConfiguration.class, OtherSchedulingConfiguration.class);
        if (!outer.equals("missing")) runner = runner.withPropertyValues("pawbridge.animal-maintenance.enabled=" + outer);
        if (!cleanup.equals("missing")) runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=" + cleanup);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBeansOfType(JdbcAnimalOutboxRetention.class)).isEmpty();
            assertThat(context.getBeansOfType(AnimalMaintenanceConfiguration.OutboxRetentionJobs.class)).isEmpty();
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
        });
    }

    @Test
    void given__both_gates_enabled__when__assembling_without_running_tasks__then__reuse_named_writer_and_register_the_job_once() {
        var jdbc = mock(JdbcTemplate.class); var manager = mock(PlatformTransactionManager.class);
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemDefaultZone());
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        new ApplicationContextRunner().withUserConfiguration(AnimalMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.animal-maintenance.enabled=true", "pawbridge.jobs.cleanup-enabled=true")
                .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("readJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeTransactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean("readTransactionManager", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean("writeDataSource", javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class))
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("animalMaintenanceScheduler", scheduler)))
                .run(context -> {
                    assertThat(context).hasNotFailed(); assertThat(context).hasSingleBean(JdbcAnimalOutboxRetention.class);
                    var retention = context.getBean(JdbcAnimalOutboxRetention.class);
                    assertThat(retention).isInstanceOf(JdbcAnimalOutboxRetention.class);
                    assertThat(ReflectionTestUtils.getField(retention, "jdbc")).isSameAs(jdbc);
                    var transaction = (TransactionTemplate) ReflectionTestUtils.getField(retention, "transactions");
                    assertThat(transaction.getTransactionManager()).isSameAs(manager);
                    assertThat(transaction.getTimeout()).isEqualTo(-1); assertThat(transaction.isReadOnly()).isFalse();
                    assertThat(((Clock) ReflectionTestUtils.getField(retention, "clock")).getZone()).isEqualTo(ZoneId.systemDefault());
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AnimalMaintenanceConfiguration.OutboxRetentionJobs.class)).hasSize(1);
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
                    verify(scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
                    verify(jdbc).afterPropertiesSet(); verifyNoMoreInteractions(jdbc); verifyNoInteractions(manager);
                });
    }

    @Test
    void given__enabled_without_writer__when__starting__then__fail_closed() {
        new ApplicationContextRunner().withUserConfiguration(AnimalMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.animal-maintenance.enabled=true", "pawbridge.jobs.cleanup-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void given__animal_job__when__reading_schedule_contract__then__keep_three_in_default_jvm_zone_and_register_the_configuration_once() throws Exception {
        var outbox = AnimalMaintenanceConfiguration.OutboxRetentionJobs.class.getMethod("cleanupOldOutboxEvents").getAnnotation(Scheduled.class);
        assertThat(outbox.cron()).isEqualTo("0 0 3 * * ?"); assertThat(outbox.zone()).isEmpty();
        assertThat(outbox.scheduler()).isEqualTo("animalMaintenanceScheduler");
        assertThat(Arrays.stream(PawBridgeApplication.class.getAnnotation(Import.class).value()).filter(type -> type == AnimalMaintenanceConfiguration.class).count()).isEqualTo(1);
    }

    @Test
    void given__other_module_maintenance_gates__when__only_animal_gates_are_open__then__assemble_nothing_for_community_user_or_store() {
        new ApplicationContextRunner().withUserConfiguration(AnimalMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.community-maintenance.enabled=true", "pawbridge.user-maintenance.enabled=true",
                        "pawbridge.store-maintenance.enabled=true", "pawbridge.search.rebuild-enabled=true", "pawbridge.jobs.cleanup-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(JdbcAnimalOutboxRetention.class)).isEmpty();
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
                });
    }

    @Test
    void given__transaction_boundary_failure__when__job_invoked__then__propagate_to_scheduler_error_handler() {
        var failure = new IllegalStateException("synthetic failure");
        var jdbc = mock(org.springframework.jdbc.core.JdbcOperations.class);
        var jobs = new AnimalMaintenanceConfiguration.OutboxRetentionJobs(new JdbcAnimalOutboxRetention(jdbc, new org.springframework.transaction.support.TransactionOperations() {
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) { throw failure; }
        }));
        assertThatThrownBy(jobs::cleanupOldOutboxEvents).isSameAs(failure);
        verifyNoInteractions(jdbc);
    }
}
