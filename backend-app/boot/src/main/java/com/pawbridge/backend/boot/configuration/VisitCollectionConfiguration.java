package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollection;

import com.pawbridge.backend.community.internal.visit.application.VisitCollection;
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionStore;
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity;
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollectionStore;
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionController;
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionExceptionHandler;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.visits", name = "collection-enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class VisitCollectionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(VisitCollectionConfiguration.class);

    public VisitCollectionConfiguration(@Value("${pawbridge.visits.enabled:false}") boolean readEnabled) {
        if (!readEnabled) throw new IllegalStateException("Visit collection requires pawbridge.visits.enabled=true.");
    }
    @Bean
    VisitIdentity visitIdentity(@Value("${pawbridge.visits.signing-key:}") String signingKey) {
        return new VisitIdentity(signingKey);
    }
    @Bean
    VisitCollectionStore visitCollectionStore(@Qualifier("writeJdbc") JdbcTemplate jdbc) {
        return new JdbcVisitCollectionStore(jdbc);
    }
    @Bean
    VisitCollection visitCollection(VisitCollectionStore store, VisitIdentity identity,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,
            @Qualifier("readClock") Clock clock) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        return new JdbcVisitCollection(store, identity, clock, transaction);
    }
    @Bean
    VisitCollectionController visitCollectionController(VisitCollection service, VisitIdentity identity,
            @Qualifier("readClock") Clock clock,
            @Value("${pawbridge.visits.allowed-origins:https://www.pawbridge.kr}") String origins) {
        return new VisitCollectionController(service, identity, clock, origins);
    }
    @Bean VisitCollectionExceptionHandler visitCollectionExceptionHandler() { return new VisitCollectionExceptionHandler(); }
    @Bean
    @DependsOn("writeDataSource")
    ThreadPoolTaskScheduler visitCollectionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("visit-collection-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(failure -> log.warn("Visit collection cleanup failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }
    @Bean VisitCollectionLifecycle visitCollectionLifecycle(VisitCollection service) { return new VisitCollectionLifecycle(service); }

    public static final class VisitCollectionLifecycle {
        private final VisitCollection service;
        public VisitCollectionLifecycle(VisitCollection service) { this.service = service; }
        @EventListener(ApplicationReadyEvent.class)
        public void start() { service.start(); service.cleanup(); }
        @Scheduled(scheduler = "visitCollectionScheduler", initialDelay = 60000, fixedDelay = 60000)
        public void cleanup() { service.cleanup(); }
    }
}
