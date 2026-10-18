package com.pawbridge.storeservice.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("postgresql")
@ConditionalOnProperty(name="pawbridge.search.rebuild-enabled",havingValue="true")
public class SearchRebuildScheduler {
    private final PostgresqlSearchDocuments documents;
    public SearchRebuildScheduler(PostgresqlSearchDocuments documents) { this.documents=documents; }
    @Scheduled(fixedDelayString="${pawbridge.search.rebuild-delay-ms:5000}")
    public void rebuild() { documents.rebuildPage(100); }
}
