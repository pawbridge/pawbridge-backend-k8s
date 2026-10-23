package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentCompensationMetricsConfigurationTest {
    private static final String GAUGE = "pawbridge.payment.compensation.open";
    private static final Duration MINUTE = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AtomicReference<TransactionDefinition> definition = new AtomicReference<>();

    private ApplicationContextRunner runner() {
        when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            definition.set(call.getArgument(0));
            return new SimpleTransactionStatus();
        });
        var readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentCompensationMetricsConfiguration.class)
                .withBean("readJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("readOnlyTransaction", TransactionTemplate.class, () -> readOnly);
    }

    @Test
    void given__payments_not_enabled__when__starting__then__no_gauge_is_registered() {
        runner().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MeterBinder.class));
    }

    @Test
    void given__payments_explicitly_disabled__when__starting__then__no_gauge_is_registered() {
        runner().withPropertyValues("pawbridge.payments.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MeterBinder.class));
    }

    @Test
    void given__payments_enabled__when__binding__then__the_gauge_reports_the_unresolved_rows_in_a_read_only_transaction() {
        when(jdbc.queryForObject(eq(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL), eq(Long.class))).thenReturn(3L);

        runner().withPropertyValues("pawbridge.payments.enabled=true").run(context -> {
            var registry = new SimpleMeterRegistry();
            context.getBean(MeterBinder.class).bindTo(registry);

            assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(3.0);
            assertThat(definition.get().isReadOnly()).isTrue();
        });
    }

    @Test
    void given__the_count_query__when__reading_the_sql__then__it_counts_only_rows_without_resolved_at() {
        assertThat(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL)
                .isEqualTo("select count(*) from pawbridge_payment.payment_compensation_records where resolved_at is null");
    }

    @Test
    void given__frequent_scrapes__when__reading_the_gauge__then__the_database_is_queried_at_most_once_per_interval() {
        when(jdbc.queryForObject(eq(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL), eq(Long.class)))
                .thenReturn(2L, 5L);

        runner().withPropertyValues("pawbridge.payments.enabled=true").run(context -> {
            var registry = new SimpleMeterRegistry();
            context.getBean(MeterBinder.class).bindTo(registry);
            var gauge = registry.get(GAUGE).gauge();

            assertThat(gauge.value()).isEqualTo(2.0);
            assertThat(gauge.value()).isEqualTo(2.0);
            assertThat(gauge.value()).isEqualTo(2.0);
            verify(jdbc, times(1)).queryForObject(eq(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL), eq(Long.class));
        });
    }

    @Test
    void given__interval_elapsed__when__reading__then__the_count_is_refreshed() {
        var clock = new AtomicLong();
        var counts = new AtomicLong(1);
        var cache = new PaymentCompensationMetricsConfiguration.CachedOpenCount(counts::getAndIncrement, clock::get, MINUTE);

        assertThat(cache.getAsDouble()).isEqualTo(1.0);
        clock.set(MINUTE.toNanos() - 1);
        assertThat(cache.getAsDouble()).isEqualTo(1.0);
        clock.set(MINUTE.toNanos());
        assertThat(cache.getAsDouble()).isEqualTo(2.0);
        assertThat(cache.getAsDouble()).isEqualTo(2.0);
    }

    @Test
    void given__the_database_fails__when__reading__then__report_nan_and_do_not_retry_before_the_interval() {
        var clock = new AtomicLong();
        var calls = new AtomicLong();
        var cache = new PaymentCompensationMetricsConfiguration.CachedOpenCount(() -> {
            calls.incrementAndGet();
            throw new DataAccessResourceFailureException("unreachable");
        }, clock::get, MINUTE);

        assertThat(cache.getAsDouble()).isNaN();
        assertThat(cache.getAsDouble()).isNaN();
        assertThat(calls).hasValue(1);
        clock.set(MINUTE.toNanos());
        assertThat(cache.getAsDouble()).isNaN();
        assertThat(calls).hasValue(2);
    }

    @Test
    void given__a_failure_followed_by_recovery__when__the_interval_passes__then__the_real_count_returns() {
        var clock = new AtomicLong();
        var healthy = new java.util.concurrent.atomic.AtomicBoolean(false);
        var cache = new PaymentCompensationMetricsConfiguration.CachedOpenCount(() -> {
            if (!healthy.get()) throw new DataAccessResourceFailureException("unreachable");
            return 4L;
        }, clock::get, MINUTE);

        assertThat(cache.getAsDouble()).isNaN();
        healthy.set(true);
        clock.set(MINUTE.toNanos());
        assertThat(cache.getAsDouble()).isEqualTo(4.0);
    }
}
