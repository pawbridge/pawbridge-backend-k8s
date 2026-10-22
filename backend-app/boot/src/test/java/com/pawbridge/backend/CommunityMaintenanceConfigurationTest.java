package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.boot.configuration.CommunityMaintenanceConfiguration;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

class CommunityMaintenanceConfigurationTest {
    @ParameterizedTest @ValueSource(strings = {"false", "true"})
    void given__feature_disabled_or_jobs_not_explicitly_enabled__when__starting__then__create_no_workers_or_scheduler(String enabled) {
        new ApplicationContextRunner().withUserConfiguration(CommunityMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.community-maintenance.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(JdbcPostSearchRebuild.class)).isEmpty();
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
                });
    }

    @Test
    void given__canonical_jobs_enabled_but_feature_missing__when__starting__then__do_not_claim_the_existing_scheduler_ownership() {
        new ApplicationContextRunner().withUserConfiguration(CommunityMaintenanceConfiguration.class)
                .withPropertyValues("pawbridge.jobs.cleanup-enabled=true", "pawbridge.search.rebuild-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(JdbcPostSearchRebuild.class)).isEmpty();
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
                });
    }

    @Test
    void given__both_jobs_explicitly_enabled__when__assembling_without_running_tasks__then__reuse_writer_and_write_transaction() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(JdbcTransactionManager.class);
        var write = new TransactionTemplate(manager); write.setTimeout(10);
        var writer = mock(JdbcPostWriter.class);
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.getClock()).thenReturn(java.time.Clock.systemUTC());
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(java.time.Instant.class), any(Duration.class));
        new ApplicationContextRunner().withUserConfiguration(CommunityMaintenanceConfiguration.class)
                .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> manager)
                .withBean("writeTransaction", TransactionTemplate.class, () -> write)
                .withBean("postWriter", JdbcPostWriter.class, () -> writer)
                .withBean("writeDataSource", javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class))
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory ->
                        factory.registerSingleton("communityMaintenanceScheduler", scheduler)))
                .withPropertyValues("pawbridge.community-maintenance.enabled=true", "pawbridge.jobs.cleanup-enabled=true",
                        "pawbridge.search.rebuild-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var rebuild = context.getBean(JdbcPostSearchRebuild.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(rebuild, "jdbc")).isSameAs(jdbc);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(rebuild, "transactions")).isSameAs(write);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(rebuild, "projection")).isSameAs(writer);
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(CommunityMaintenanceConfiguration.SearchRebuildJob.class)).hasSize(1);
                    verify(jdbc, never()).queryForList(org.mockito.ArgumentMatchers.anyString(), any(Object[].class));
                    verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(), any(Object[].class));
                    verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class));
                });
    }

    @Test
    void given__a_job_failure__when__invoked__then__propagate_it_to_the_scheduler_without_continuing_other_work() {
        var rebuild = mock(JdbcPostSearchRebuild.class);
        var failure = new IllegalStateException("synthetic maintenance failure");
        when(rebuild.rebuildPage(100)).thenThrow(failure);
        assertThatThrownBy(() -> new CommunityMaintenanceConfiguration.SearchRebuildJob(rebuild).rebuild()).isSameAs(failure);
        verify(rebuild).rebuildPage(100);
        verifyNoMoreInteractions(rebuild);
    }
}
