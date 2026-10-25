package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.StoreMaintenanceConfiguration
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import com.pawbridge.backend.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.function.Supplier
import javax.sql.DataSource

class StoreMaintenanceConfigurationTest {
    @ParameterizedTest
    @CsvSource("missing,missing", "true,missing", "missing,true", "false,true", "true,false", "false,false")
    fun given__either_gate_closed_or_missing__when__starting_without_writer__then__create_no_rebuild_or_scheduling(
        outer: String,
        rebuild: String,
    ) {
        var runner = ApplicationContextRunner().withUserConfiguration(StoreMaintenanceConfiguration::class.java)
        if (outer != "missing") {
            runner = runner.withPropertyValues("pawbridge.store-maintenance.enabled=$outer")
        }
        if (rebuild != "missing") {
            runner = runner.withPropertyValues("pawbridge.search.rebuild-enabled=$rebuild")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(JdbcProductSearchRebuild::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMaintenanceConfiguration.SearchRebuildJob::class.java)).isEmpty()
            assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        }
    }

    @Test
    fun given__both_gates_enabled__when__assembling_without_running_tasks__then__reuse_named_writer_with_original_timeouts_and_register_one_job() {
        val source = mock(DataSource::class.java)
        val manager = mock(JdbcTransactionManager::class.java)
        val scheduler = mock(ThreadPoolTaskScheduler::class.java)
        val future = mock(ScheduledFuture::class.java)
        whenever(scheduler.clock).thenReturn(Clock.systemUTC())
        doReturn(future).`when`(scheduler).scheduleWithFixedDelay(anyNonNull<Runnable>(), anyNonNull<Duration>())
        doReturn(future).`when`(scheduler)
            .scheduleWithFixedDelay(anyNonNull<Runnable>(), anyNonNull<Instant>(), anyNonNull<Duration>())
        ApplicationContextRunner().withUserConfiguration(StoreMaintenanceConfiguration::class.java)
            .withPropertyValues("pawbridge.store-maintenance.enabled=true", "pawbridge.search.rebuild-enabled=true")
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor(
                    BeanFactoryPostProcessor { factory ->
                        factory.registerSingleton("storeMaintenanceScheduler", scheduler)
                    },
                )
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                val rebuild = context.getBean(JdbcProductSearchRebuild::class.java)
                val jdbc = context.getBean("storeSearchRebuildJdbc", NamedParameterJdbcTemplate::class.java)
                assertThat(ReflectionTestUtils.getField(rebuild, "jdbc")).isSameAs(jdbc)
                assertThat(jdbc.jdbcTemplate.dataSource).isSameAs(source)
                assertThat(jdbc.jdbcTemplate.queryTimeout).isEqualTo(5)
                val transaction = ReflectionTestUtils.getField(rebuild, "transactions") as TransactionTemplate
                assertThat(transaction).isSameAs(context.getBean("storeSearchRebuildTransaction"))
                assertThat(transaction.transactionManager).isSameAs(manager)
                assertThat(transaction.timeout).isEqualTo(10)
                assertThat(transaction.isReadOnly).isFalse()
                assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(StoreMaintenanceConfiguration.SearchRebuildJob::class.java)).hasSize(1)
                assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor::class.java).scheduledTasks).hasSize(1)
                verifyNoInteractions(source)
            }
    }

    @Test
    fun given__store_rebuild_job__when__reading_schedule_contract__then__keep_the_original_fixed_delay_on_the_store_scheduler() {
        val scheduled = StoreMaintenanceConfiguration.SearchRebuildJob::class.java.getMethod("rebuild")
            .getAnnotation(Scheduled::class.java)
        assertThat(scheduled.fixedDelayString).isEqualTo("\${pawbridge.search.rebuild-delay-ms:5000}")
        assertThat(scheduled.scheduler).isEqualTo("storeMaintenanceScheduler")
        assertThat(
            PawBridgeApplication::class.java.getAnnotation(Import::class.java).value
                .filter { type -> type.java == StoreMaintenanceConfiguration::class.java }
                .size,
        ).isEqualTo(1)
    }

    @Test
    fun given__a_rebuild_failure__when__invoked__then__propagate_it_to_the_scheduler_after_one_original_page() {
        val rebuild = mock(JdbcProductSearchRebuild::class.java)
        val failure = IllegalStateException("synthetic maintenance failure")
        whenever(rebuild.rebuildPage(100)).thenThrow(failure)
        assertThatThrownBy { StoreMaintenanceConfiguration.SearchRebuildJob(rebuild).rebuild() }.isSameAs(failure)
        verify(rebuild).rebuildPage(100)
        verifyNoMoreInteractions(rebuild)
    }
}
