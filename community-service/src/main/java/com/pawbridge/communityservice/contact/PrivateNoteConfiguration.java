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
    private final PrivateNoteService notes;
    public PrivateNoteConfiguration(PrivateNoteService notes) { this.notes=notes; }
    @Bean public static Clock privateNoteClock() {return Clock.systemUTC();}
    @Scheduled(fixedDelay=300000,initialDelay=300000)
    public void expireNotes() { notes.expire(); }
}
