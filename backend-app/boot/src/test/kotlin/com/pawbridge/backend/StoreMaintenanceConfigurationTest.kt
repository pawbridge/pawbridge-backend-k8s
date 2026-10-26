package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.StoreMaintenanceConfiguration
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    fun `한쪽 게이트가 닫혔거나 없을 때__작성기 없이 시작하면__재색인과 스케줄링을 만들지 않는다`(
        outer: String,
        rebuild: String,
    ) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(StoreMaintenanceConfiguration::class.java)
        if (outer != "missing") {
            runner = runner.withPropertyValues("pawbridge.store-maintenance.enabled=$outer")
        }
        if (rebuild != "missing") {
            runner = runner.withPropertyValues("pawbridge.search.rebuild-enabled=$rebuild")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(JdbcProductSearchRebuild::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMaintenanceConfiguration.SearchRebuildJob::class.java)).isEmpty()
            assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        }
    }

    @Test
    fun `두 게이트가 모두 켜졌을 때__작업 실행 없이 조립하면__기존 타임아웃의 이름 있는 작성기를 재사용하고 작업을 하나 등록한다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<JdbcTransactionManager>()
        val scheduler = mockk<ThreadPoolTaskScheduler>()
        val future = mockk<ScheduledFuture<*>>()

        justRun { manager.afterPropertiesSet() }
        excludeRecords { source.hashCode() }
        every { scheduler.isAutoStartup } returns false
        every { scheduler.clock } returns Clock.systemUTC()
        every { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) } returns future
        every { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Instant>(), any<Duration>()) } returns future

        // when
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
                // then
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
                confirmVerified(source)
            }
    }

    @Test
    fun `재색인 작업이 있을 때__스케줄 계약을 읽으면__스토어 스케줄러의 기존 고정 지연을 유지한다`() {
        // given
        val method = StoreMaintenanceConfiguration.SearchRebuildJob::class.java.getMethod("rebuild")

        // when
        val scheduled = method.getAnnotation(Scheduled::class.java)

        // then
        assertThat(scheduled.fixedDelayString).isEqualTo("\${pawbridge.search.rebuild-delay-ms:5000}")
        assertThat(scheduled.scheduler).isEqualTo("storeMaintenanceScheduler")
        assertThat(
            PawBridgeApplication::class.java.getAnnotation(Import::class.java).value
                .filter { type -> type.java == StoreMaintenanceConfiguration::class.java }
                .size,
        ).isEqualTo(1)
    }

    @Test
    fun `재색인이 실패했을 때__호출하면__기존 한 페이지 뒤에 스케줄러로 예외를 전파한다`() {
        // given
        val rebuild = mockk<JdbcProductSearchRebuild>()
        val failure = IllegalStateException("synthetic maintenance failure")

        every { rebuild.rebuildPage(any()) } throws failure

        // when
        val thrown = assertThatThrownBy { StoreMaintenanceConfiguration.SearchRebuildJob(rebuild).rebuild() }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { rebuild.rebuildPage(100) }
        confirmVerified(rebuild)
    }
}
