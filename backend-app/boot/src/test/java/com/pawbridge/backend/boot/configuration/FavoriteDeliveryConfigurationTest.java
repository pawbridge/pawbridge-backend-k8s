package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents;
import com.pawbridge.backend.user.internal.favoritedelivery.infrastructure.JdbcFavoriteEventDelivery;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

class FavoriteDeliveryConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(FavoriteDeliveryConfiguration.class);
    }

    private ApplicationContextRunner inputs() {
        return runner().withBean("writeJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeDataSource", DataSource.class, () -> mock(DataSource.class))
                .withBean("writeTransactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.favorite-delivery.enabled=true",
                        "pawbridge.favorite-delivery.user-outbox-floor=12",
                        "pawbridge.favorite-delivery.animal-outbox-floor=23",
                        "pawbridge.favorite-delivery.delay-ms=3600000",
                        "pawbridge.user-animal-activities.enabled=true");
    }

    @Test
    void given__no_explicit_enable__when__assembling_without_database__then__no_delivery_or_scheduler() {
        runner().withPropertyValues("pawbridge.favorite-delivery.user-outbox-floor=0",
                "pawbridge.favorite-delivery.animal-outbox-floor=0").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(AnimalFavoriteEvents.class)
                        .doesNotHaveBean(JdbcFavoriteEventDelivery.class)
                        .doesNotHaveBean(ThreadPoolTaskScheduler.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pawbridge.favorite-delivery.user-outbox-floor=-1",
            "pawbridge.favorite-delivery.animal-outbox-floor=-1",
            "pawbridge.favorite-delivery.batch-size=0", "pawbridge.favorite-delivery.batch-size=101",
            "pawbridge.favorite-delivery.delay-ms=99", "pawbridge.user-animal-activities.enabled=false",
            "pawbridge.user-animal-activities.enabled=yes", "pawbridge.user-animal-activities.enabled=on",
            "pawbridge.user-animal-activities.enabled=1"})
    void given__invalid_or_implicit_delivery_boundary__when__enabled__then__fail_before_database_access(String property) {
        inputs().withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void given__missing_both_floors__when__enabled__then__refuse_historical_replay() {
        runner().withPropertyValues("pawbridge.favorite-delivery.enabled=true",
                "pawbridge.user-animal-activities.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Explicit nonnegative outbox floors are required");
        });
    }

    @Test
    void given__unconditional_cleanup_enabled__when__enabling_local_delivery__then__refuse_pending_event_loss() {
        inputs().withPropertyValues("pawbridge.user-maintenance.enabled=true", "pawbridge.jobs.cleanup-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Unconditional event retention cannot run with local delivery");
                });
    }

    @ParameterizedTest
    @CsvSource({"yes,true", "true,on", "1,true"})
    void given__cleanup_alias_does_not_activate_its_owner__when__assembling__then__do_not_block_local_delivery(String maintenance, String cleanup) {
        inputs().withPropertyValues("pawbridge.user-maintenance.enabled=" + maintenance,
                "pawbridge.jobs.cleanup-enabled=" + cleanup).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JdbcFavoriteEventDelivery.class);
            verifyNoInteractions(context.getBean("writeTransactionManager"));
        });
    }

    @Test
    void given__explicit_floors_and_no_cleanup__when__assembling__then__one_poller_without_startup_queries() {
        inputs().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AnimalFavoriteEvents.class)
                    .hasSingleBean(JdbcFavoriteEventDelivery.class).hasSingleBean(ThreadPoolTaskScheduler.class);
            assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            verifyNoInteractions(context.getBean("writeTransactionManager"));
            verify(context.getBean("writeJdbc", JdbcTemplate.class)).afterPropertiesSet();
            verifyNoMoreInteractions(context.getBean("writeJdbc"));
        });
    }

    @Test
    void given__a_configured_job__when__polling_and_closing_its_context__then__use_fixed_floors_and_stop_before_disposal() {
        var delivery = mock(JdbcFavoriteEventDelivery.class);
        var own = mock(ApplicationContext.class);
        var job = new FavoriteDeliveryConfiguration.FavoriteDeliveryJob(delivery, own, 12, 23, 10);
        job.deliver();
        verify(delivery).poll(12, 23, 10);
        job.onApplicationEvent(new ContextClosedEvent(mock(ApplicationContext.class)));
        verify(delivery, never()).stop();
        job.onApplicationEvent(new ContextClosedEvent(own));
        verify(delivery).stop();
        verifyNoMoreInteractions(delivery);
        assertThat(job.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }
}
