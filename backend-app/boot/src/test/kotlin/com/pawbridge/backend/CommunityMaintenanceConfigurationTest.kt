package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.CommunityMaintenanceConfiguration
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.anyVarargs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.function.Supplier
import javax.sql.DataSource

class CommunityMaintenanceConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["false", "true"])
    fun given__feature_disabled_or_jobs_not_explicitly_enabled__when__starting__then__create_no_workers_or_scheduler(
        enabled: String,
    ) {
        ApplicationContextRunner().withUserConfiguration(CommunityMaintenanceConfiguration::class.java)
            .withPropertyValues("pawbridge.community-maintenance.enabled=$enabled")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(JdbcPostSearchRebuild::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            }
    }

    @Test
    fun given__canonical_jobs_enabled_but_feature_missing__when__starting__then__do_not_claim_the_existing_scheduler_ownership() {
        ApplicationContextRunner().withUserConfiguration(CommunityMaintenanceConfiguration::class.java)
            .withPropertyValues("pawbridge.jobs.cleanup-enabled=true", "pawbridge.search.rebuild-enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(JdbcPostSearchRebuild::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            }
    }

    @Test
    fun given__both_jobs_explicitly_enabled__when__assembling_without_running_tasks__then__reuse_writer_and_write_transaction() {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(JdbcTransactionManager::class.java)
        val write = TransactionTemplate(manager)
        write.timeout = 10
        val writer = mock(JdbcPostWriter::class.java)
        val scheduler = mock(ThreadPoolTaskScheduler::class.java)
        val future = mock(ScheduledFuture::class.java)
        whenever(scheduler.clock).thenReturn(Clock.systemUTC())
        doReturn(future).`when`(scheduler).schedule(anyNonNull<Runnable>(), anyNonNull<Trigger>())
        doReturn(future).`when`(scheduler).scheduleWithFixedDelay(anyNonNull<Runnable>(), anyNonNull<Duration>())
        doReturn(future).`when`(scheduler)
            .scheduleWithFixedDelay(anyNonNull<Runnable>(), anyNonNull<Instant>(), anyNonNull<Duration>())
        ApplicationContextRunner().withUserConfiguration(CommunityMaintenanceConfiguration::class.java)
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("writeTransaction", TransactionTemplate::class.java, Supplier { write })
            .withBean("postWriter", JdbcPostWriter::class.java, Supplier { writer })
            .withBean("writeDataSource", DataSource::class.java, Supplier { mock(DataSource::class.java) })
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
            .run { context ->
                assertThat(context).hasNotFailed()
                val rebuild = context.getBean(JdbcPostSearchRebuild::class.java)
                assertThat(ReflectionTestUtils.getField(rebuild, "jdbc")).isSameAs(jdbc)
                assertThat(ReflectionTestUtils.getField(rebuild, "transactions")).isSameAs(write)
                assertThat(ReflectionTestUtils.getField(rebuild, "projection")).isSameAs(writer)
                assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CommunityMaintenanceConfiguration.SearchRebuildJob::class.java)).hasSize(1)
                verify(jdbc, never()).queryForList(anyString(), *anyVarargs())
                verify(jdbc, never()).update(anyString(), *anyVarargs())
                verify(jdbc, never()).update(anyNonNull<PreparedStatementCreator>())
            }
    }

    @Test
    fun given__a_job_failure__when__invoked__then__propagate_it_to_the_scheduler_without_continuing_other_work() {
        val rebuild = mock(JdbcPostSearchRebuild::class.java)
        val failure = IllegalStateException("synthetic maintenance failure")
        whenever(rebuild.rebuildPage(100)).thenThrow(failure)
        assertThatThrownBy { CommunityMaintenanceConfiguration.SearchRebuildJob(rebuild).rebuild() }.isSameAs(failure)
        verify(rebuild).rebuildPage(100)
        verifyNoMoreInteractions(rebuild)
    }
}
