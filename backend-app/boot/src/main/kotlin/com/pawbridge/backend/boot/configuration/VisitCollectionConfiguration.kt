package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionStore
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollection
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollectionStore
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionController
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionExceptionHandler
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.visits", name = ["collection-enabled"], havingValue = "true", matchIfMissing = false)
@EnableScheduling
class VisitCollectionConfiguration(
    @Value("\${pawbridge.visits.enabled:false}") readEnabled: Boolean,
) {
    init {
        if (!readEnabled) {
            throw IllegalStateException("Visit collection requires pawbridge.visits.enabled=true.")
        }
    }

    @Bean
    fun visitIdentity(@Value("\${pawbridge.visits.signing-key:}") signingKey: String): VisitIdentity {
        return VisitIdentity(signingKey)
    }

    @Bean
    fun visitCollectionStore(@Qualifier("writeJdbc") jdbc: JdbcTemplate): VisitCollectionStore {
        return JdbcVisitCollectionStore(jdbc)
    }

    @Bean
    fun visitCollection(
        store: VisitCollectionStore,
        identity: VisitIdentity,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        @Qualifier("readClock") clock: Clock,
    ): VisitCollection {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 10
        return JdbcVisitCollection(store, identity, clock, transaction)
    }

    @Bean
    fun visitCollectionController(
        service: VisitCollection,
        identity: VisitIdentity,
        @Qualifier("readClock") clock: Clock,
        @Value("\${pawbridge.visits.allowed-origins:https://www.pawbridge.kr}") origins: String,
    ): VisitCollectionController {
        return VisitCollectionController(service, identity, clock, origins)
    }

    @Bean
    fun visitCollectionExceptionHandler(): VisitCollectionExceptionHandler {
        return VisitCollectionExceptionHandler()
    }

    @Bean
    @DependsOn("writeDataSource")
    fun visitCollectionScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("visit-collection-")
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(false)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(5)
        scheduler.setErrorHandler(
            ErrorHandler { failure -> log.warn("Visit collection cleanup failed ({})", failure.javaClass.simpleName) },
        )
        return scheduler
    }

    @Bean
    fun visitCollectionLifecycle(service: VisitCollection): VisitCollectionLifecycle {
        return VisitCollectionLifecycle(service)
    }

    class VisitCollectionLifecycle(private val service: VisitCollection) {
        @EventListener(ApplicationReadyEvent::class)
        fun start() {
            service.start()
            service.cleanup()
        }

        @Scheduled(scheduler = "visitCollectionScheduler", initialDelay = 60000, fixedDelay = 60000)
        fun cleanup() {
            service.cleanup()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(VisitCollectionConfiguration::class.java)
    }
}
