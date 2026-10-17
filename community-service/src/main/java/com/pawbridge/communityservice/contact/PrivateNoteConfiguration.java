package com.pawbridge.communityservice.contact;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class PrivateNoteConfiguration {
    private final PrivateNoteService privateNoteService;
    private final boolean cleanupEnabled;

    public PrivateNoteConfiguration(PrivateNoteService privateNoteService,
            @Value("${pawbridge.jobs.cleanup-enabled:true}") boolean cleanupEnabled) {
        this.privateNoteService = privateNoteService;
        this.cleanupEnabled = cleanupEnabled;
    }

    @Bean
    public static Clock privateNoteClock() {
        return Clock.systemUTC();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void expireNotes() {
        if (cleanupEnabled) privateNoteService.expire();
    }
}
