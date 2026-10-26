package com.pawbridge.backend.boot.configuration

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import java.time.Duration
import java.util.function.DoubleSupplier
import java.util.function.LongSupplier
import java.util.function.ToDoubleFunction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * Operator-facing gauge of payment compensation records that still need manual attention.
 * Registered only when payments are enabled, because the table belongs to that feature.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.payments", name = ["enabled"], havingValue = "true")
class PaymentCompensationMetricsConfiguration {
    @Bean
    fun paymentCompensationOpenGauge(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") readOnly: TransactionTemplate,
    ): MeterBinder {
        val query = LongSupplier {
            // java.lang.Long (not the primitive class) is the type the JDBC query is asked to return.
            val count: Long? = readOnly.execute { _ -> jdbc.queryForObject(OPEN_COUNT_SQL, Long::class.javaObjectType) }
            count ?: 0L
        }
        val openCount = CachedOpenCount(query, LongSupplier { System.nanoTime() }, CachedOpenCount.REFRESH_INTERVAL)
        return MeterBinder { registry ->
            Gauge.builder(GAUGE_NAME, openCount, ToDoubleFunction<CachedOpenCount> { it.getAsDouble() })
                .description(
                    "Payment compensation records not yet resolved (resolved_at IS NULL); " +
                        "NaN while the count cannot be read",
                )
                .strongReference(true)
                .register(registry)
        }
    }

    /**
     * Reads the count at most once per interval, however often Prometheus scrapes. A failed read is cached for
     * the same interval and reported as NaN, so an unreachable database neither hides the problem behind a stale
     * number nor makes every scrape wait for the connection timeout.
     */
    class CachedOpenCount(
        private val query: LongSupplier,
        private val nanoClock: LongSupplier,
        interval: Duration,
    ) : DoubleSupplier {
        private val intervalNanos: Long = interval.toNanos()
        private var value: Double = Double.NaN
        private var readAt: Long = 0
        private var read: Boolean = false

        @Synchronized
        override fun getAsDouble(): Double {
            val now = nanoClock.asLong
            if (!read || now - readAt >= intervalNanos) {
                try {
                    value = query.asLong.toDouble()
                } catch (failure: RuntimeException) {
                    value = Double.NaN
                    LOG.warn("Cannot read the open payment compensation count: {}", failure.javaClass.simpleName)
                }
                read = true
                readAt = now
            }
            return value
        }

        companion object {
            @JvmField
            val REFRESH_INTERVAL: Duration = Duration.ofSeconds(60)

            private val LOG = LoggerFactory.getLogger(CachedOpenCount::class.java)
        }
    }

    companion object {
        const val GAUGE_NAME: String = "pawbridge.payment.compensation.open"
        const val OPEN_COUNT_SQL: String =
            "select count(*) from pawbridge_payment.payment_compensation_records where resolved_at is null"
    }
}
