package com.pawbridge.communityservice.visits;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="pawbridge.visits.enabled", havingValue="true")
public class VisitConfiguration {
    @Bean
    VisitCookie visitCookie(@Value("${pawbridge.visits.signing-key}") String key) { return new VisitCookie(key); }
    @Bean
    VisitService visitService(VisitRepository repository, VisitCookie cookie, Clock clock, PlatformTransactionManager manager) {
        return new VisitService(repository,cookie,clock,manager);
    }
    @Bean
    VisitLifecycle visitLifecycle(VisitService service) { return new VisitLifecycle(service); }

    static class VisitLifecycle {
        private final VisitService service;
        VisitLifecycle(VisitService service) { this.service=service; }
        @EventListener(ApplicationReadyEvent.class)
        public void start() { service.start(); service.cleanup(); }
        @Scheduled(fixedDelay=60_000, initialDelay=60_000)
        public void cleanup() { service.cleanup(); }
    }
}
