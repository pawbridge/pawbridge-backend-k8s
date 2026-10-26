package com.pawbridge.backend.boot.configuration

import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    private val jdbc = mockk<JdbcTemplate>()
    private val manager = mockk<PlatformTransactionManager>()
    private val definition = AtomicReference<TransactionDefinition>()

    private fun runner(): ApplicationContextRunner {
        val readOnly = TransactionTemplate(manager)
        readOnly.isReadOnly = true
        return ApplicationContextRunner()
            .withUserConfiguration(PaymentCompensationMetricsConfiguration::class.java)
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("readOnlyTransaction", TransactionTemplate::class.java, Supplier { readOnly })
    }

    @Test
    fun `결제가 켜져 있지 않을 때__시작하면__게이지를 등록하지 않는다`() {
        // given
        val runner = runner()

        justRun { jdbc.afterPropertiesSet() }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed().doesNotHaveBean(MeterBinder::class.java)
        }
    }

    @Test
    fun `결제가 명시적으로 꺼져 있을 때__시작하면__게이지를 등록하지 않는다`() {
        // given
        val runner = runner().withPropertyValues("pawbridge.payments.enabled=false")

        justRun { jdbc.afterPropertiesSet() }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed().doesNotHaveBean(MeterBinder::class.java)
        }
    }

    @Test
    fun `결제가 켜져 있을 때__바인딩하면__게이지가 읽기 전용 트랜잭션으로 미해결 건수를 보고한다`() {
        // given
        val runner = runner().withPropertyValues("pawbridge.payments.enabled=true")

        justRun { jdbc.afterPropertiesSet() }
        every { jdbc.queryForObject(any<String>(), any<Class<Long>>()) } returns 3L
        every { manager.getTransaction(any()) } answers {
            definition.set(firstArg<TransactionDefinition>())
            SimpleTransactionStatus()
        }
        justRun { manager.commit(any()) }

        runner.run { context ->
            // when
            val registry = SimpleMeterRegistry()
            context.getBean(MeterBinder::class.java).bindTo(registry)

            // then
            assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(3.0)
            assertThat(definition.get().isReadOnly).isTrue()
        }
    }

    @Test
    fun `건수 조회 SQL이 주어졌을 때__SQL을 읽으면__resolved_at이 없는 행만 센다`() {
        // given
        // when
        val sql = PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL

        // then
        assertThat(sql)
            .isEqualTo("select count(*) from pawbridge_payment.payment_compensation_records where resolved_at is null")
    }

    @Test
    fun `스크랩이 잦을 때__게이지를 읽으면__주기당 최대 한 번만 DB를 조회한다`() {
        // given
        val runner = runner().withPropertyValues("pawbridge.payments.enabled=true")

        justRun { jdbc.afterPropertiesSet() }
        every { jdbc.queryForObject(any<String>(), any<Class<Long>>()) } returnsMany listOf(2L, 5L)
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }

        runner.run { context ->
            val registry = SimpleMeterRegistry()
            context.getBean(MeterBinder::class.java).bindTo(registry)
            val gauge = registry.get(GAUGE).gauge()

            // when
            val readings = listOf(gauge.value(), gauge.value(), gauge.value())

            // then
            assertThat(readings).containsExactly(2.0, 2.0, 2.0)
            verify(exactly = 1) {
                jdbc.queryForObject(
                    PaymentCompensationMetricsConfiguration.OPEN_COUNT_SQL,
                    Long::class.javaObjectType,
                )
            }
        }
    }

    @Test
    fun `주기가 지났을 때__읽으면__건수를 새로 읽는다`() {
        // given
        val clock = AtomicLong()
        val counts = AtomicLong(1)
        val cache = PaymentCompensationMetricsConfiguration.CachedOpenCount(
            LongSupplier { counts.getAndIncrement() },
            LongSupplier { clock.get() },
            MINUTE,
        )

        // when
        val first = cache.getAsDouble()
        clock.set(MINUTE.toNanos() - 1)
        val justBeforeInterval = cache.getAsDouble()
        clock.set(MINUTE.toNanos())
        val atInterval = cache.getAsDouble()
        val afterRefresh = cache.getAsDouble()

        // then
        assertThat(first).isEqualTo(1.0)
        assertThat(justBeforeInterval).isEqualTo(1.0)
        assertThat(atInterval).isEqualTo(2.0)
        assertThat(afterRefresh).isEqualTo(2.0)
    }

    @Test
    fun `DB가 실패할 때__읽으면__NaN을 보고하고 주기 전에는 재시도하지 않는다`() {
        // given
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

        // when
        val first = cache.getAsDouble()
        val second = cache.getAsDouble()
        val callsBeforeInterval = calls.get()
        clock.set(MINUTE.toNanos())
        val afterInterval = cache.getAsDouble()

        // then
        assertThat(first).isNaN()
        assertThat(second).isNaN()
        assertThat(callsBeforeInterval).isEqualTo(1)
        assertThat(afterInterval).isNaN()
        assertThat(calls).hasValue(2)
    }

    @Test
    fun `실패 뒤 복구됐을 때__주기가 지나면__실제 건수가 돌아온다`() {
        // given
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

        // when
        val whileFailing = cache.getAsDouble()
        healthy.set(true)
        clock.set(MINUTE.toNanos())
        val afterRecovery = cache.getAsDouble()

        // then
        assertThat(whileFailing).isNaN()
        assertThat(afterRecovery).isEqualTo(4.0)
    }

    companion object {
        private const val GAUGE = "pawbridge.payment.compensation.open"
        private val MINUTE: Duration = Duration.ofSeconds(60)
    }
}
