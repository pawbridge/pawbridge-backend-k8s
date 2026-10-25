package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.support.anyValue
import com.pawbridge.backend.support.eqValue
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import java.util.function.Supplier

class PaymentCompensationMetricsConfigurationTest {
    private val jdbc = mock(JdbcTemplate::class.java)
    private val manager = mock(PlatformTransactionManager::class.java)
    private val definition = AtomicReference<TransactionDefinition>()

    private fun runner(): ApplicationContextRunner {
        whenever(manager.getTransaction(anyValue<TransactionDefinition>())).thenAnswer(
            Answer<TransactionStatus> { call ->
                definition.set(call.getArgument<TransactionDefinition>(0))
                SimpleTransactionStatus()
            },
        )
        val readOnly = TransactionTemplate(manager)
        readOnly.isReadOnly = true
        return ApplicationContextRunner()
            .withUserConfiguration(PaymentCompensationMetricsConfiguration::class.java)
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("readOnlyTransaction", TransactionTemplate::class.java, Supplier { readOnly })
    }

    private fun openCount(): Long? {
        return jdbc.queryForObject(
            eqValue(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL),
            eqValue(Long::class.javaObjectType),
        )
    }

    @Test
    fun given__payments_not_enabled__when__starting__then__no_gauge_is_registered() {
        runner().run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(MeterBinder::class.java) }
    }

    @Test
    fun given__payments_explicitly_disabled__when__starting__then__no_gauge_is_registered() {
        runner().withPropertyValues("pawbridge.payments.enabled=false")
            .run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(MeterBinder::class.java) }
    }

    @Test
    fun given__payments_enabled__when__binding__then__the_gauge_reports_the_unresolved_rows_in_a_read_only_transaction() {
        whenever(openCount()).thenReturn(3L)

        runner().withPropertyValues("pawbridge.payments.enabled=true").run { context ->
            val registry = SimpleMeterRegistry()
            context.getBean(MeterBinder::class.java).bindTo(registry)

            assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(3.0)
            assertThat(definition.get().isReadOnly).isTrue()
        }
    }

    @Test
    fun given__the_count_query__when__reading_the_sql__then__it_counts_only_rows_without_resolved_at() {
        assertThat(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL)
            .isEqualTo("select count(*) from pawbridge_payment.payment_compensation_records where resolved_at is null")
    }

    @Test
    fun given__frequent_scrapes__when__reading_the_gauge__then__the_database_is_queried_at_most_once_per_interval() {
        whenever(openCount()).thenReturn(2L, 5L)

        runner().withPropertyValues("pawbridge.payments.enabled=true").run { context ->
            val registry = SimpleMeterRegistry()
            context.getBean(MeterBinder::class.java).bindTo(registry)
            val gauge = registry.get(GAUGE).gauge()

            assertThat(gauge.value()).isEqualTo(2.0)
            assertThat(gauge.value()).isEqualTo(2.0)
            assertThat(gauge.value()).isEqualTo(2.0)
            verify(jdbc, times(1)).queryForObject(
                eqValue(PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL),
                eqValue(Long::class.javaObjectType),
            )
        }
    }

    @Test
    fun given__interval_elapsed__when__reading__then__the_count_is_refreshed() {
        val clock = AtomicLong()
        val counts = AtomicLong(1)
        val cache = PaymentCompensationMetricsConfiguration.CachedOpenCount(
            LongSupplier { counts.getAndIncrement() },
            LongSupplier { clock.get() },
            MINUTE,
        )

        assertThat(cache.getAsDouble()).isEqualTo(1.0)
        clock.set(MINUTE.toNanos() - 1)
        assertThat(cache.getAsDouble()).isEqualTo(1.0)
        clock.set(MINUTE.toNanos())
        assertThat(cache.getAsDouble()).isEqualTo(2.0)
        assertThat(cache.getAsDouble()).isEqualTo(2.0)
    }

    @Test
    fun given__the_database_fails__when__reading__then__report_nan_and_do_not_retry_before_the_interval() {
        val clock = AtomicLong()
        val calls = AtomicLong()
        val cache = PaymentCompensationMetricsConfiguration.CachedOpenCount(
            LongSupplier {
                calls.incrementAndGet()
                throw DataAccessResourceFailureException("unreachable")
            },
            LongSupplier { clock.get() },
            MINUTE,
        )

        assertThat(cache.getAsDouble()).isNaN()
        assertThat(cache.getAsDouble()).isNaN()
        assertThat(calls).hasValue(1)
        clock.set(MINUTE.toNanos())
        assertThat(cache.getAsDouble()).isNaN()
        assertThat(calls).hasValue(2)
    }

    @Test
    fun given__a_failure_followed_by_recovery__when__the_interval_passes__then__the_real_count_returns() {
        val clock = AtomicLong()
        val healthy = AtomicBoolean(false)
        val cache = PaymentCompensationMetricsConfiguration.CachedOpenCount(
            LongSupplier {
                if (!healthy.get()) {
                    throw DataAccessResourceFailureException("unreachable")
                }
                4L
            },
            LongSupplier { clock.get() },
            MINUTE,
        )

        assertThat(cache.getAsDouble()).isNaN()
        healthy.set(true)
        clock.set(MINUTE.toNanos())
        assertThat(cache.getAsDouble()).isEqualTo(4.0)
    }

    companion object {
        private const val GAUGE = "pawbridge.payment.compensation.open"
        private val MINUTE: Duration = Duration.ofSeconds(60)
    }
}
