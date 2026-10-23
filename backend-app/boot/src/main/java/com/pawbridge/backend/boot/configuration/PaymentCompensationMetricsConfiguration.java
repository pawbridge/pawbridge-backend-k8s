package com.pawbridge.backend.boot.configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator-facing gauge of payment compensation records that still need manual attention.
 * Registered only when payments are enabled, because the table belongs to that feature.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.payments", name = "enabled", havingValue = "true")
public class PaymentCompensationMetricsConfiguration {
    static final String GAUGE_NAME = "pawbridge.payment.compensation.open";
    static final String OPEN_COUNT_SQL =
            "select count(*) from pawbridge_payment.payment_compensation_records where resolved_at is null";

    @Bean
    MeterBinder paymentCompensationOpenGauge(@Qualifier("readJdbc") JdbcTemplate jdbc,
            @Qualifier("readOnlyTransaction") TransactionTemplate readOnly) {
        LongSupplier query = () -> {
            Long count = readOnly.execute(status -> jdbc.queryForObject(OPEN_COUNT_SQL, Long.class));
            return count == null ? 0L : count;
        };
        var openCount = new CachedOpenCount(query, System::nanoTime, CachedOpenCount.REFRESH_INTERVAL);
        return registry -> Gauge.builder(GAUGE_NAME, openCount, CachedOpenCount::getAsDouble)
                .description("Payment compensation records not yet resolved (resolved_at IS NULL); NaN while the count cannot be read")
                .strongReference(true)
                .register(registry);
    }

    /**
     * Reads the count at most once per interval, however often Prometheus scrapes. A failed read is cached for
     * the same interval and reported as NaN, so an unreachable database neither hides the problem behind a stale
     * number nor makes every scrape wait for the connection timeout.
     */
    static final class CachedOpenCount implements DoubleSupplier {
        static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);
        private static final Logger LOG = LoggerFactory.getLogger(CachedOpenCount.class);

        private final LongSupplier query;
        private final LongSupplier nanoClock;
        private final long intervalNanos;
        private double value = Double.NaN;
        private long readAt;
        private boolean read;

        CachedOpenCount(LongSupplier query, LongSupplier nanoClock, Duration interval) {
            this.query = query;
            this.nanoClock = nanoClock;
            this.intervalNanos = interval.toNanos();
        }

        @Override
        public synchronized double getAsDouble() {
            long now = nanoClock.getAsLong();
            if (!read || now - readAt >= intervalNanos) {
                try {
                    value = query.getAsLong();
                } catch (RuntimeException failure) {
                    value = Double.NaN;
                    LOG.warn("Cannot read the open payment compensation count: {}", failure.getClass().getSimpleName());
                }
                read = true;
                readAt = now;
            }
            return value;
        }
    }
}
