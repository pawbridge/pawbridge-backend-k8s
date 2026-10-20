package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.UserMaintenanceConfiguration;
import com.pawbridge.backend.user.api.UserEventRetention;
import com.pawbridge.backend.user.internal.maintenance.infrastructure.JdbcUserEventRetention;
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

class UserMaintenanceConfigurationTest {
    @ParameterizedTest
    @CsvSource({"missing,missing", "true,missing", "missing,true", "false,true", "true,false", "false,false"})
    void given__either_gate_closed_or_missing__when__starting_without_writer__then__create_no_maintenance_or_scheduling(String outer, String cleanup) {
        var runner = new ApplicationContextRunner().withUserConfiguration(UserMaintenanceConfiguration.class);
        if (!outer.equals("missing")) runner = runner.withPropertyValues("pawbridge.user-maintenance.enabled=" + outer);
        if (!cleanup.equals("missing")) runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=" + cleanup);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(UserEventRetention.class)).isEmpty();
            assertThat(context.getBeansOfType(UserMaintenanceConfiguration.EventRetentionJobs.class)).isEmpty();
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class OtherSchedulingConfiguration { }

    @ParameterizedTest
    @CsvSource({"missing,missing", "true,missing", "missing,true", "false,true", "true,false", "false,false"})
    void given__another_feature_enables_scheduling__when__either_user_gate_is_closed__then__do_not_register_user_workers(String outer, String cleanup) {
        var runner = new ApplicationContextRunner().withUserConfiguration(UserMaintenanceConfiguration.class, OtherSchedulingConfiguration.class);
        if (!outer.equals("missing")) runner = runner.withPropertyValues("pawbridge.user-maintenance.enabled=" + outer);
        if (!cleanup.equals("missing")) runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=" + cleanup);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBeansOfType(UserEventRetention.class)).isEmpty();
            assertThat(context.getBeansOfType(UserMaintenanceConfiguration.EventRetentionJobs.class)).isEmpty();
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
        });
    }

    @Test
    void given__both_gates_enabled__when__assembling_without_running_tasks__then__reuse_named_writer_and_register_each_job_once() {
        var jdbc = mock(JdbcTemplate.class); var manager = mock(PlatformTransactionManager.class);
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemDefaultZone());
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        new ApplicationContextRunner().withUserConfiguration(UserMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.user-maintenance.enabled=true", "pawbridge.jobs.cleanup-enabled=true")
                .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("readJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeTransactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean("readTransactionManager", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean("writeDataSource", javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class))
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("userMaintenanceScheduler", scheduler)))
                .run(context -> {
                    assertThat(context).hasNotFailed(); assertThat(context).hasSingleBean(UserEventRetention.class);
                    var retention = context.getBean(UserEventRetention.class);
                    assertThat(retention).isInstanceOf(JdbcUserEventRetention.class);
                    assertThat(ReflectionTestUtils.getField(retention, "jdbc")).isSameAs(jdbc);
                    var transaction = (TransactionTemplate) ReflectionTestUtils.getField(retention, "transactions");
                    assertThat(transaction.getTransactionManager()).isSameAs(manager);
                    assertThat(transaction.getTimeout()).isEqualTo(-1); assertThat(transaction.isReadOnly()).isFalse();
                    assertThat(((Clock) ReflectionTestUtils.getField(retention, "clock")).getZone()).isEqualTo(ZoneId.systemDefault());
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(UserMaintenanceConfiguration.EventRetentionJobs.class)).hasSize(1);
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(2);
                    verify(scheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
                    verify(jdbc).afterPropertiesSet(); verifyNoMoreInteractions(jdbc); verifyNoInteractions(manager);
                });
    }

    @Test
    void given__enabled_without_writer__when__starting__then__fail_closed() {
        new ApplicationContextRunner().withUserConfiguration(UserMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.user-maintenance.enabled=true", "pawbridge.jobs.cleanup-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void given__user_jobs__when__reading_schedule_contract__then__keep_three_and_three_thirty_in_default_jvm_zone() throws Exception {
        var outbox = UserMaintenanceConfiguration.EventRetentionJobs.class.getMethod("cleanupOldOutboxEvents").getAnnotation(Scheduled.class);
        var processed = UserMaintenanceConfiguration.EventRetentionJobs.class.getMethod("cleanupOldProcessedEvents").getAnnotation(Scheduled.class);
        assertThat(outbox.cron()).isEqualTo("0 0 3 * * *"); assertThat(processed.cron()).isEqualTo("0 30 3 * * *");
        assertThat(outbox.zone()).isEmpty(); assertThat(processed.zone()).isEmpty();
        assertThat(outbox.scheduler()).isEqualTo("userMaintenanceScheduler"); assertThat(processed.scheduler()).isEqualTo(outbox.scheduler());
        assertThat(Arrays.stream(PawBridgeApplication.class.getAnnotation(Import.class).value()).filter(type -> type == UserMaintenanceConfiguration.class).count()).isEqualTo(1);
    }

    @Test
    void given__transaction_boundary_failure__when__job_invoked__then__propagate_to_scheduler_error_handler() {
        var retention = mock(UserEventRetention.class); var failure = new IllegalStateException("synthetic failure");
        doThrow(failure).when(retention).cleanupOldOutboxEvents(); doThrow(failure).when(retention).cleanupOldProcessedEvents();
        var jobs = new UserMaintenanceConfiguration.EventRetentionJobs(retention);
        assertThatThrownBy(jobs::cleanupOldOutboxEvents).isSameAs(failure);
        assertThatThrownBy(jobs::cleanupOldProcessedEvents).isSameAs(failure);
        verify(retention).cleanupOldOutboxEvents(); verify(retention).cleanupOldProcessedEvents(); verifyNoMoreInteractions(retention);
    }
}
