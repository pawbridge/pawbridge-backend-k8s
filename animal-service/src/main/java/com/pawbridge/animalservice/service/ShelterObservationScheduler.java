package com.pawbridge.animalservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "postgresql")
public class ShelterObservationScheduler {
    private final ShelterObservationRecorder recorder;

    @Scheduled(cron = "${pawbridge.shelter-observations.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void record() {
        log.info("Shelter daily observations recorded: {}", recorder.recordToday());
    }
}
