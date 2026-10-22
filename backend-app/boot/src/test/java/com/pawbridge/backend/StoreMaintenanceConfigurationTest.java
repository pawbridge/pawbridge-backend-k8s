package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.StoreMaintenanceConfiguration;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class StoreMaintenanceConfigurationTest {
    @ParameterizedTest
    @CsvSource({"missing,missing", "true,missing", "missing,true", "false,true", "true,false", "false,false"})
    void given__either_gate_closed_or_missing__when__starting_without_writer__then__create_no_rebuild_or_scheduling(String outer, String rebuild) {
        var runner = new ApplicationContextRunner().withUserConfiguration(StoreMaintenanceConfiguration.class);
        if (!outer.equals("missing")) runner = runner.withPropertyValues("pawbridge.store-maintenance.enabled=" + outer);
        if (!rebuild.equals("missing")) runner = runner.withPropertyValues("pawbridge.search.rebuild-enabled=" + rebuild);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(JdbcProductSearchRebuild.class)).isEmpty();
            assertThat(context.getBeansOfType(StoreMaintenanceConfiguration.SearchRebuildJob.class)).isEmpty();
            assertThat(context.getBeansOfType(KoreanSearchTerms.class)).isEmpty();
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
    }

    @Test
    void given__both_gates_enabled__when__assembling_without_running_tasks__then__reuse_named_writer_with_original_timeouts_and_register_one_job() {
        var source = mock(javax.sql.DataSource.class);
        var manager = mock(JdbcTransactionManager.class);
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.getClock()).thenReturn(java.time.Clock.systemUTC());
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        new ApplicationContextRunner().withUserConfiguration(StoreMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.store-maintenance.enabled=true", "pawbridge.search.rebuild-enabled=true")
                .withBean("writeDataSource", javax.sql.DataSource.class, () -> source)
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> manager)
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory ->
                        factory.registerSingleton("storeMaintenanceScheduler", scheduler)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var rebuild = context.getBean(JdbcProductSearchRebuild.class);
                    var jdbc = context.getBean("storeSearchRebuildJdbc", NamedParameterJdbcTemplate.class);
                    assertThat(ReflectionTestUtils.getField(rebuild, "jdbc")).isSameAs(jdbc);
                    assertThat(jdbc.getJdbcTemplate().getDataSource()).isSameAs(source);
                    assertThat(jdbc.getJdbcTemplate().getQueryTimeout()).isEqualTo(5);
                    var transaction = (TransactionTemplate) ReflectionTestUtils.getField(rebuild, "transactions");
                    assertThat(transaction).isSameAs(context.getBean("storeSearchRebuildTransaction"));
                    assertThat(transaction.getTransactionManager()).isSameAs(manager);
                    assertThat(transaction.getTimeout()).isEqualTo(10);
                    assertThat(transaction.isReadOnly()).isFalse();
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(StoreMaintenanceConfiguration.SearchRebuildJob.class)).hasSize(1);
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
                    verifyNoInteractions(source);
                });
    }

    @Test
    void given__store_rebuild_job__when__reading_schedule_contract__then__keep_the_original_fixed_delay_on_the_store_scheduler() throws Exception {
        var scheduled = StoreMaintenanceConfiguration.SearchRebuildJob.class.getMethod("rebuild")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.fixedDelayString()).isEqualTo("${pawbridge.search.rebuild-delay-ms:5000}");
        assertThat(scheduled.scheduler()).isEqualTo("storeMaintenanceScheduler");
        assertThat(java.util.Arrays.stream(PawBridgeApplication.class
                .getAnnotation(org.springframework.context.annotation.Import.class).value())
                .filter(type -> type == StoreMaintenanceConfiguration.class).count()).isEqualTo(1);
    }

    @Test
    void given__a_rebuild_failure__when__invoked__then__propagate_it_to_the_scheduler_after_one_original_page() {
        var rebuild = mock(JdbcProductSearchRebuild.class);
        var failure = new IllegalStateException("synthetic maintenance failure");
        when(rebuild.rebuildPage(100)).thenThrow(failure);
        assertThatThrownBy(() -> new StoreMaintenanceConfiguration.SearchRebuildJob(rebuild).rebuild()).isSameAs(failure);
        verify(rebuild).rebuildPage(100);
        verifyNoMoreInteractions(rebuild);
    }
}
