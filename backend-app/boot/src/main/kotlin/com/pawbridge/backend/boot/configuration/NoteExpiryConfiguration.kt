package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteExpiry
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteExpiry
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.jobs", name = ["cleanup-enabled"], havingValue = "true", matchIfMissing = false)
@EnableScheduling
class NoteExpiryConfiguration {
    @Bean
    fun noteExpiry(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        @Qualifier("readClock") clock: Clock,
    ): NoteExpiry {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteExpiry(jdbc, transaction, clock)
    }

    @Bean
    @DependsOn("writeDataSource")
    fun noteExpiryScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("private-note-expiry-")
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(false)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(5)
        scheduler.setErrorHandler(
            ErrorHandler { failure -> log.warn("Private note expiry failed ({})", failure.javaClass.simpleName) },
        )
        return scheduler
    }

    @Bean
    fun noteExpiryJob(expiry: NoteExpiry): NoteExpiryJob {
        return NoteExpiryJob(expiry)
    }

    class NoteExpiryJob(private val expiry: NoteExpiry) {
        @Scheduled(scheduler = "noteExpiryScheduler", initialDelay = 300000, fixedDelay = 300000)
        fun expire() {
            expiry.expire()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NoteExpiryConfiguration::class.java)
    }
}
