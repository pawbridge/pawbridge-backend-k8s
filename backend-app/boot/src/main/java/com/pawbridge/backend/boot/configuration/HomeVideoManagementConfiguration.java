package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement;
import com.pawbridge.backend.community.internal.homevideo.infrastructure.*;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.home-videos",name="management-enabled",havingValue="true",matchIfMissing=false)
@Import(HomeVideoManagementConfiguration.RefreshConfiguration.class)
public class HomeVideoManagementConfiguration {
    @Bean(destroyMethod="close") HttpClient homeVideoHttpClient(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();}
    @Bean YouTubeVideoClient youTubeVideoClient(ObjectMapper mapper,@Value("${youtube.api-key:${YOUTUBE_DATA_API_KEY:}}") String key,
            @Qualifier("homeVideoHttpClient") HttpClient http){return new YouTubeVideoClient(mapper,key,http,URI.create("https://www.googleapis.com/youtube/v3/videos"));}
    @Bean JdbcHomeVideoRepository homeVideoManagementRepository(@Qualifier("writeJdbc") JdbcTemplate jdbc){return new JdbcHomeVideoRepository(jdbc);}
    @Bean HomeVideoManagement homeVideoManagement(JdbcHomeVideoRepository repository,YouTubeVideoClient youtube,
            @Qualifier("readClock") Clock clock,@Qualifier("writeTransactionManager") JdbcTransactionManager manager){
        return new JdbcHomeVideoManagement(repository,youtube,clock,new TransactionTemplate(manager));
    }
    @Bean HomeVideoManagementController homeVideoManagementController(HomeVideoManagement management){return new HomeVideoManagementController(management);}
    @Bean HomeVideoManagementExceptionHandler homeVideoManagementExceptionHandler(){return new HomeVideoManagementExceptionHandler();}

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="pawbridge.jobs",name="refresh-enabled",havingValue="true",matchIfMissing=false)
    @EnableScheduling
    static class RefreshConfiguration {
        @Bean @DependsOn({"writeDataSource","homeVideoHttpClient"}) ThreadPoolTaskScheduler homeVideoRefreshScheduler(){
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("home-video-refresh-");
            scheduler.setAcceptTasksAfterContextClose(false);scheduler.setWaitForTasksToCompleteOnShutdown(false);
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            scheduler.setRemoveOnCancelPolicy(true);scheduler.setAwaitTerminationSeconds(5);
            scheduler.setErrorHandler(failure->org.slf4j.LoggerFactory.getLogger(HomeVideoManagementConfiguration.class).warn("Video refresh scheduling failed ({})",failure.getClass().getSimpleName()));
            return scheduler;
        }
        @Bean HomeVideoRefreshJob homeVideoRefreshJob(HomeVideoManagement management){return new HomeVideoRefreshJob(management);}
    }
    public static final class HomeVideoRefreshJob {
        private final HomeVideoManagement management;
        public HomeVideoRefreshJob(HomeVideoManagement management){this.management=management;}
        @Scheduled(scheduler="homeVideoRefreshScheduler",fixedDelay=3_600_000,initialDelay=60_000)
        public void refresh(){try{management.refresh();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(HomeVideoRefreshJob.class).warn("YouTube metadata refresh unavailable; retry on next schedule ({})",failure.getClass().getSimpleName());}}
    }
}
