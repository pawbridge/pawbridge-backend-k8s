package com.pawbridge.communityservice.contact;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile("postgresql")
@EnableScheduling
public class PrivateNoteConfiguration {
    private final PrivateNoteService privateNoteService;

    public PrivateNoteConfiguration(PrivateNoteService privateNoteService) {
        this.privateNoteService = privateNoteService;
    }

    @Bean
    public static Clock privateNoteClock() {
        return Clock.systemUTC();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void expireNotes() {
        privateNoteService.expire();
    }
}
