package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import com.pawbridge.backend.support.anyValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.net.http.HttpClient
import java.time.Clock
import java.util.function.Supplier

class HomeVideoManagementConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__management_disabled__when__assembling__then__create_no_http_management_or_jobs(flag: String) {
        var runner = ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.home-videos.management-enabled=false")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(HomeVideoManagement::class.java)
                .doesNotHaveBean(YouTubeVideoClient::class.java)
                .doesNotHaveBean(HttpClient::class.java)
                .doesNotHaveBean(TaskScheduler::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__management_enabled_without_refresh__when__assembling__then__reuse_writer_and_source_default_transaction_without_scheduler(
        flag: String,
    ) {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(JdbcTransactionManager::class.java)
        val clock = Clock.systemUTC()
        var runner = ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration::class.java)
            .withPropertyValues("pawbridge.home-videos.management-enabled=true", "youtube.api-key=synthetic-key")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("readClock", Clock::class.java, Supplier { clock })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.jobs.refresh-enabled=false")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .hasSingleBean(HomeVideoManagement::class.java)
                .doesNotHaveBean(TaskScheduler::class.java)
            val service = context.getBean(HomeVideoManagement::class.java)
            assertThat(ReflectionTestUtils.getField(service, "clock")).isSameAs(clock)
            val tx = ReflectionTestUtils.getField(service, "transaction") as TransactionTemplate
            assertThat(tx.transactionManager).isSameAs(manager)
            assertThat(tx.timeout).isEqualTo(-1)
            assertThat(ReflectionTestUtils.getField(context.getBean("homeVideoManagementRepository"), "jdbc"))
                .isSameAs(jdbc)
            assertThat(ReflectionTestUtils.getField(context.getBean(YouTubeVideoClient::class.java), "http"))
                .isSameAs(context.getBean("homeVideoHttpClient"))
            verify(manager, never()).getTransaction(anyValue<TransactionDefinition>())
        }
    }

    @Test
    fun given__the_explicit_refresh_job__when__inspecting_and_running_a_provider_failure__then__retain_source_timing_and_safe_retry_on_next_schedule() {
        val scheduled = HomeVideoManagementConfiguration.HomeVideoRefreshJob::class.java.getMethod("refresh")
            .getAnnotation(Scheduled::class.java)
        assertThat(scheduled.initialDelay).isEqualTo(60_000)
        assertThat(scheduled.fixedDelay).isEqualTo(3_600_000)
        assertThat(scheduled.scheduler).isEqualTo("homeVideoRefreshScheduler")
        val management = mock(HomeVideoManagement::class.java)
        doThrow(IllegalStateException("synthetic-private-key")).`when`(management).refresh()
        assertThatCode { HomeVideoManagementConfiguration.HomeVideoRefreshJob(management).refresh() }
            .doesNotThrowAnyException()
        verify(management).refresh()
    }
}
