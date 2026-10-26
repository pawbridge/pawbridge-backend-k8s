package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.CommunityMaintenanceConfiguration
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.function.Supplier
import javax.sql.DataSource

class CommunityMaintenanceConfigurationTest {
    // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
    private fun jdbcTemplate(): JdbcTemplate {
        val bean = mockk<JdbcTemplate>()
        justRun { bean.afterPropertiesSet() }
        return bean
    }

    // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
    private fun transactionManager(): JdbcTransactionManager {
        val bean = mockk<JdbcTransactionManager>()
        justRun { bean.afterPropertiesSet() }
        return bean
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "true"])
    fun `기능이 꺼졌거나 작업이 켜지지 않았을 때__기동하면__작업자와 스케줄러를 만들지 않는다`(
        enabled: String,
    ) {
        // given
        val runner = ApplicationContextRunner()
            .withUserConfiguration(CommunityMaintenanceConfiguration::class.java)
            .withPropertyValues("pawbridge.community-maintenance.enabled=$enabled")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(JdbcPostSearchRebuild::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
        }
    }

    @Test
    fun `정식 작업은 켜졌지만 기능 설정이 없을 때__기동하면__기존 스케줄러 소유권을 가져가지 않는다`() {
        // given
        val runner = ApplicationContextRunner()
            .withUserConfiguration(CommunityMaintenanceConfiguration::class.java)
            .withPropertyValues("pawbridge.jobs.cleanup-enabled=true", "pawbridge.search.rebuild-enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(JdbcPostSearchRebuild::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
        }
    }

    @Test
    fun `두 작업이 명시적으로 켜졌을 때__작업 실행 없이 조립하면__재색인 작업을 하나 등록하고 DB를 건드리지 않는다`() {
        // given
        val jdbc = jdbcTemplate()
        val manager = transactionManager()
        val write = TransactionTemplate(manager)
        write.timeout = 10
        val writer = mockk<JdbcPostWriter>()
        val scheduler = mockk<ThreadPoolTaskScheduler>()
        val future = mockk<ScheduledFuture<*>>()
        val dataSource = mockk<DataSource>()

        every { scheduler.clock } returns Clock.systemUTC()
        // The registered singleton is also managed as a lifecycle bean, so a strict mock must answer these callbacks.
        every { scheduler.isAutoStartup } returns false
        every { scheduler.schedule(any<Runnable>(), any<Trigger>()) } returns future
        every { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) } returns future
        every {
            scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Instant>(), any<Duration>())
        } returns future

        val runner = ApplicationContextRunner()
            .withUserConfiguration(CommunityMaintenanceConfiguration::class.java)
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("writeTransaction", TransactionTemplate::class.java, Supplier { write })
            .withBean("postWriter", JdbcPostWriter::class.java, Supplier { writer })
            .withBean("writeDataSource", DataSource::class.java, Supplier { dataSource })
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor(
                    BeanFactoryPostProcessor { factory ->
                        factory.registerSingleton("communityMaintenanceScheduler", scheduler)
                    },
                )
            }
            .withPropertyValues(
                "pawbridge.community-maintenance.enabled=true",
                "pawbridge.jobs.cleanup-enabled=true",
                "pawbridge.search.rebuild-enabled=true",
            )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(JdbcPostSearchRebuild::class.java)
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(CommunityMaintenanceConfiguration.SearchRebuildJob::class.java)).hasSize(1)
            verify(exactly = 0) { jdbc.queryForList(any<String>(), *anyVararg<Any>()) }
            verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg<Any>()) }
            verify(exactly = 0) { jdbc.update(any<PreparedStatementCreator>()) }
        }
    }

    @Test
    fun `작업이 실패했을 때__호출하면__다른 작업을 이어가지 않고 스케줄러로 전파한다`() {
        // given
        val rebuild = mockk<JdbcPostSearchRebuild>()
        val failure = IllegalStateException("synthetic maintenance failure")

        every { rebuild.rebuildPage(100) } throws failure

        // when
        val thrown = assertThatThrownBy { CommunityMaintenanceConfiguration.SearchRebuildJob(rebuild).rebuild() }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { rebuild.rebuildPage(100) }
        confirmVerified(rebuild)
    }
}
