package com.pawbridge.backend.boot.configuration;

import java.net.URI;
import org.springframework.core.env.Environment;

/** Settings exist only for the explicitly enabled APMS schedule or manual API. Never render the service key. */
final class ApmsScheduleSettings {
    private final int workers;
    private final int transactionTimeout;
    private final int maxRequests;
    private final int maxAnimals;
    private final String serviceKey;
    private final URI baseUri;
    private final String cron;

    ApmsScheduleSettings(Environment environment) {
        workers = environment.getProperty("apms.batch.workers", Integer.class, 1);
        transactionTimeout = environment.getProperty("apms.batch.step-timeout-seconds", Integer.class, 15);
        maxRequests = environment.getProperty("apms.sync.max-requests", Integer.class, 100);
        maxAnimals = environment.getProperty("apms.sync.max-animals", Integer.class, 50000);
        if (workers < 1 || workers > 4) throw new IllegalArgumentException("APMS workers must be between one and four");
        if (transactionTimeout < 1 || maxRequests < 1 || maxAnimals < 1)
            throw new IllegalArgumentException("APMS timeout and collection limits must be positive");
        serviceKey = environment.getProperty("apms.api.service-key");
        if (serviceKey == null || serviceKey.isBlank() || serviceKey.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("APMS service key is required");
        baseUri = ApmsHttpClient.baseUri(environment.getProperty("apms.api.base-url"));
        cron = environment.getProperty("apms.batch.collection-cron", "0 */30 * * * *");
    }

    int workers() { return workers; }
    int transactionTimeout() { return transactionTimeout; }
    int maxRequests() { return maxRequests; }
    int maxAnimals() { return maxAnimals; }
    String serviceKey() { return serviceKey; }
    URI baseUri() { return baseUri; }
    String cron() { return cron; }

    void requireCapacity(int maximumPoolSize) {
        if (maximumPoolSize < workers + 2)
            throw new IllegalArgumentException("APMS writer capacity must cover workers, session lock and metadata");
    }
}
