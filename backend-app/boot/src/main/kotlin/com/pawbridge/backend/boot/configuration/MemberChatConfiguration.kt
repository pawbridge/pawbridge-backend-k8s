package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.infrastructure.JdbcMemberChat
import com.pawbridge.backend.community.internal.chat.infrastructure.JdbcMemberChatRepository
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
@ConditionalOnProperty(name = ["pawbridge.chat.enabled"], havingValue = "true")
@EnableScheduling
@Import(
    MemberChatTransportConfiguration::class,
    MemberChatSocketConfiguration::class,
    MemberChatConfiguration.RetentionConfiguration::class,
)
class MemberChatConfiguration {
    @Bean
    fun memberChatRepository(@Qualifier("writeJdbc") jdbc: JdbcTemplate): JdbcMemberChatRepository {
        return JdbcMemberChatRepository(jdbc)
    }

    @Bean
    fun memberChat(
        repository: JdbcMemberChatRepository,
        contact: NoteSendStore,
        users: ContactMemberQuery,
        hints: MemberChatHints,
        @Qualifier("readClock") clock: Clock,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): MemberChat {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcMemberChat(repository, contact, users, hints, clock, transaction)
    }

    @Bean
    @DependsOn("writeDataSource", "memberChatRedisConnectionFactory")
    fun memberChatMaintenanceScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("member-chat-maintenance-")
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(false)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(5)
        scheduler.setErrorHandler(
            ErrorHandler { failure ->
                LoggerFactory.getLogger(MemberChatConfiguration::class.java)
                    .warn("Chat maintenance failed ({})", failure.javaClass.simpleName)
            },
        )
        return scheduler
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ["pawbridge.jobs.cleanup-enabled"], havingValue = "true", matchIfMissing = true)
    internal class RetentionConfiguration {
        @Bean
        fun memberChatRetention(chat: MemberChat): MemberChatRetention {
            return MemberChatRetention(chat)
        }
    }

    class MemberChatRetention(private val chat: MemberChat) {
        @Scheduled(scheduler = "memberChatMaintenanceScheduler", fixedDelay = 300_000, initialDelay = 300_000)
        fun expire() {
            chat.expire()
        }
    }
}
