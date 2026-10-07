package com.pawbridge.communityservice.curation;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("postgresql")
public class HomeVideoRefresh {
    private final HomeVideoService service;
    public HomeVideoRefresh(HomeVideoService service) { this.service = service; }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void refresh() {
        try { service.refresh(); }
        catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(HomeVideoRefresh.class)
                    .warn("YouTube metadata refresh unavailable; retry on next schedule ({})", failure.getClass().getSimpleName());
        }
    }
}
