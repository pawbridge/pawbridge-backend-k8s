package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents;
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteEvents;
import com.pawbridge.backend.user.internal.favoritedelivery.infrastructure.JdbcFavoriteEventDelivery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local delivery is an explicit alternative to the old consumers, never a default startup action. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.favorite-delivery", name = "enabled", havingValue = "true")
@EnableScheduling
public class FavoriteDeliveryConfiguration {
    private final long userFloor;
    private final long animalFloor;
    private final int batchSize;

    public FavoriteDeliveryConfiguration(
            @Value("${pawbridge.favorite-delivery.user-outbox-floor:-1}") long userFloor,
            @Value("${pawbridge.favorite-delivery.animal-outbox-floor:-1}") long animalFloor,
            @Value("${pawbridge.favorite-delivery.batch-size:10}") int batchSize,
            @Value("${pawbridge.favorite-delivery.delay-ms:1000}") long delay,
            @Value("${pawbridge.user-animal-activities.enabled:false}") String activities,
            @Value("${pawbridge.user-maintenance.enabled:false}") String maintenance,
            @Value("${pawbridge.jobs.cleanup-enabled:false}") String cleanup) {
        if (userFloor < 0 || animalFloor < 0)
            throw new IllegalArgumentException("Explicit nonnegative outbox floors are required");
        if (batchSize < 1 || batchSize > 100 || delay < 100)
            throw new IllegalArgumentException("Bounded favorite delivery settings are required");
        // Match each owning configuration's havingValue="true"; boolean aliases would disagree with it.
        if (!"true".equalsIgnoreCase(activities))
            throw new IllegalArgumentException("The favorite producer must be enabled explicitly");
        if ("true".equalsIgnoreCase(maintenance) && "true".equalsIgnoreCase(cleanup))
            throw new IllegalArgumentException("Unconditional event retention cannot run with local delivery");
        this.userFloor = userFloor;
        this.animalFloor = animalFloor;
        this.batchSize = batchSize;
    }

    @Bean
    AnimalFavoriteEvents localAnimalFavoriteEvents(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager) {
        return new JdbcAnimalFavoriteEvents(jdbc, new TransactionTemplate(manager));
    }

    @Bean(destroyMethod = "stop")
    JdbcFavoriteEventDelivery favoriteEventDelivery(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager,
            @Qualifier("localAnimalFavoriteEvents") AnimalFavoriteEvents animal, ObjectMapper mapper) {
        // Independent short transactions on the existing one-connection pool. No transaction spans a module call.
        return new JdbcFavoriteEventDelivery(jdbc, new TransactionTemplate(manager), animal, mapper);
    }

    @Bean
    @DependsOn({"writeDataSource", "favoriteEventDelivery"})
    ThreadPoolTaskScheduler favoriteDeliveryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("favorite-delivery-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(FavoriteDeliveryConfiguration.class)
                .warn("Local favorite delivery failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }

    @Bean
    FavoriteDeliveryJob favoriteDeliveryJob(JdbcFavoriteEventDelivery delivery, ApplicationContext context) {
        return new FavoriteDeliveryJob(delivery, context, userFloor, animalFloor, batchSize);
    }

    public static final class FavoriteDeliveryJob implements ApplicationListener<ContextClosedEvent>, Ordered {
        private final JdbcFavoriteEventDelivery delivery;
        private final ApplicationContext context;
        private final long userFloor;
        private final long animalFloor;
        private final int batchSize;

        FavoriteDeliveryJob(JdbcFavoriteEventDelivery delivery, ApplicationContext context,
                long userFloor, long animalFloor, int batchSize) {
            this.delivery = delivery;
            this.context = context;
            this.userFloor = userFloor;
            this.animalFloor = animalFloor;
            this.batchSize = batchSize;
        }

        @Scheduled(scheduler = "favoriteDeliveryScheduler", fixedDelayString = "${pawbridge.favorite-delivery.delay-ms:1000}",
                initialDelayString = "${pawbridge.favorite-delivery.delay-ms:1000}")
        public void deliver() { delivery.poll(userFloor, animalFloor, batchSize); }

        @Override public void onApplicationEvent(ContextClosedEvent event) {
            if (event.getApplicationContext() == context) delivery.stop();
        }

        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    }
}
