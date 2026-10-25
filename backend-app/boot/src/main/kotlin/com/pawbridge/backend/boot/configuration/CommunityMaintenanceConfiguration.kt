package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.community-maintenance",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Import(CommunityMaintenanceConfiguration.SearchConfiguration::class)
class CommunityMaintenanceConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "pawbridge.search",
        name = ["rebuild-enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @Import(CommunityMaintenanceSchedulingConfiguration::class)
    internal class SearchConfiguration {
        @Bean
        fun communityPostSearchRebuild(
            @Qualifier("writeJdbc") jdbc: JdbcTemplate,
            @Qualifier("writeTransaction") transaction: TransactionTemplate,
            writer: JdbcPostWriter,
        ): JdbcPostSearchRebuild {
            return JdbcPostSearchRebuild(jdbc, transaction, writer)
        }

        @Bean
        fun communitySearchRebuildJob(rebuild: JdbcPostSearchRebuild): SearchRebuildJob {
            return SearchRebuildJob(rebuild)
        }
    }

    class SearchRebuildJob(private val rebuild: JdbcPostSearchRebuild) {
        @Scheduled(
            scheduler = "communityMaintenanceScheduler",
            fixedDelayString = "\${pawbridge.search.rebuild-delay-ms:5000}",
        )
        fun rebuild() {
            rebuild.rebuildPage(100)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class CommunityMaintenanceSchedulingConfiguration {
    @Bean
    @DependsOn("writeDataSource", "postWriter")
    fun communityMaintenanceScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("community-maintenance-")
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(15)
        scheduler.setErrorHandler(
            ErrorHandler { failure ->
                LoggerFactory.getLogger(CommunityMaintenanceConfiguration::class.java)
                    .warn("Community maintenance failed ({})", failure.javaClass.simpleName)
            },
        )
        return scheduler
    }
}
