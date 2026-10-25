package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.home-videos",
    name = ["management-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Import(HomeVideoManagementConfiguration.RefreshConfiguration::class)
class HomeVideoManagementConfiguration {
    @Bean(destroyMethod = "close")
    fun homeVideoHttpClient(): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    @Bean
    fun youTubeVideoClient(
        mapper: ObjectMapper,
        @Value("\${youtube.api-key:\${YOUTUBE_DATA_API_KEY:}}") key: String,
        @Qualifier("homeVideoHttpClient") http: HttpClient,
    ): YouTubeVideoClient {
        return YouTubeVideoClient(mapper, key, http, URI.create("https://www.googleapis.com/youtube/v3/videos"))
    }

    @Bean
    fun homeVideoManagementRepository(@Qualifier("writeJdbc") jdbc: JdbcTemplate): JdbcHomeVideoRepository {
        return JdbcHomeVideoRepository(jdbc)
    }

    @Bean
    fun homeVideoManagement(
        repository: JdbcHomeVideoRepository,
        youtube: YouTubeVideoClient,
        @Qualifier("readClock") clock: Clock,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): HomeVideoManagement {
        return JdbcHomeVideoManagement(repository, youtube, clock, TransactionTemplate(manager))
    }

    @Bean
    fun homeVideoManagementController(management: HomeVideoManagement): HomeVideoManagementController {
        return HomeVideoManagementController(management)
    }

    @Bean
    fun homeVideoManagementExceptionHandler(): HomeVideoManagementExceptionHandler {
        return HomeVideoManagementExceptionHandler()
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "pawbridge.jobs",
        name = ["refresh-enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @EnableScheduling
    internal class RefreshConfiguration {
        @Bean
        @DependsOn("writeDataSource", "homeVideoHttpClient")
        fun homeVideoRefreshScheduler(): ThreadPoolTaskScheduler {
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.poolSize = 1
            scheduler.setThreadNamePrefix("home-video-refresh-")
            scheduler.setAcceptTasksAfterContextClose(false)
            scheduler.setWaitForTasksToCompleteOnShutdown(false)
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            scheduler.setRemoveOnCancelPolicy(true)
            scheduler.setAwaitTerminationSeconds(5)
            scheduler.setErrorHandler(
                ErrorHandler { failure ->
                    LoggerFactory.getLogger(HomeVideoManagementConfiguration::class.java)
                        .warn("Video refresh scheduling failed ({})", failure.javaClass.simpleName)
                },
            )
            return scheduler
        }

        @Bean
        fun homeVideoRefreshJob(management: HomeVideoManagement): HomeVideoRefreshJob {
            return HomeVideoRefreshJob(management)
        }
    }

    class HomeVideoRefreshJob(private val management: HomeVideoManagement) {
        @Scheduled(scheduler = "homeVideoRefreshScheduler", fixedDelay = 3_600_000, initialDelay = 60_000)
        fun refresh() {
            try {
                management.refresh()
            } catch (failure: RuntimeException) {
                LoggerFactory.getLogger(HomeVideoRefreshJob::class.java)
                    .warn(
                        "YouTube metadata refresh unavailable; retry on next schedule ({})",
                        failure.javaClass.simpleName,
                    )
            }
        }
    }
}
