package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement;
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient;
import java.net.http.HttpClient;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class HomeVideoManagementConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__management_disabled__when__assembling__then__create_no_http_management_or_jobs(String flag){
        var runner=new ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration.class);
        if(flag.equals("false"))runner=runner.withPropertyValues("pawbridge.home-videos.management-enabled=false");
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(HomeVideoManagement.class).doesNotHaveBean(YouTubeVideoClient.class).doesNotHaveBean(HttpClient.class).doesNotHaveBean(TaskScheduler.class));
    }
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__management_enabled_without_refresh__when__assembling__then__reuse_writer_and_source_default_transaction_without_scheduler(String flag){
        var jdbc=mock(JdbcTemplate.class);var manager=mock(JdbcTransactionManager.class);var clock=Clock.systemUTC();
        var runner=new ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration.class)
                .withPropertyValues("pawbridge.home-videos.management-enabled=true","youtube.api-key=synthetic-key")
                .withBean("writeJdbc",JdbcTemplate.class,()->jdbc).withBean("writeTransactionManager",JdbcTransactionManager.class,()->manager)
                .withBean("readClock",Clock.class,()->clock).withBean(ObjectMapper.class,ObjectMapper::new);
        if(flag.equals("false"))runner=runner.withPropertyValues("pawbridge.jobs.refresh-enabled=false");
        runner.run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(HomeVideoManagement.class).doesNotHaveBean(TaskScheduler.class);
                    var service=context.getBean(HomeVideoManagement.class);assertThat(ReflectionTestUtils.getField(service,"clock")).isSameAs(clock);
                    var tx=(TransactionTemplate)ReflectionTestUtils.getField(service,"transaction");assertThat(tx.getTransactionManager()).isSameAs(manager);assertThat(tx.getTimeout()).isEqualTo(-1);
                    assertThat(ReflectionTestUtils.getField(context.getBean("homeVideoManagementRepository"),"jdbc")).isSameAs(jdbc);
                    assertThat(ReflectionTestUtils.getField(context.getBean(YouTubeVideoClient.class),"http")).isSameAs(context.getBean("homeVideoHttpClient"));verify(manager,never()).getTransaction(any());
                });
    }
    @Test void given__the_explicit_refresh_job__when__inspecting_and_running_a_provider_failure__then__retain_source_timing_and_safe_retry_on_next_schedule()throws Exception {
        var scheduled=HomeVideoManagementConfiguration.HomeVideoRefreshJob.class.getMethod("refresh").getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelay()).isEqualTo(60_000);assertThat(scheduled.fixedDelay()).isEqualTo(3_600_000);assertThat(scheduled.scheduler()).isEqualTo("homeVideoRefreshScheduler");
        var management=mock(HomeVideoManagement.class);doThrow(new IllegalStateException("synthetic-private-key")).when(management).refresh();
        assertThatCode(()->new HomeVideoManagementConfiguration.HomeVideoRefreshJob(management).refresh()).doesNotThrowAnyException();verify(management).refresh();
    }
}
