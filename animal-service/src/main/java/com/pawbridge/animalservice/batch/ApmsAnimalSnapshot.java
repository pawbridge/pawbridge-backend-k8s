package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;

import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fetched once before any animal writes; shared with shelter preparation in the same job. */
@Component
@JobScope
@Slf4j
public class ApmsAnimalSnapshot {
    private final ApmsApiClient client;
    private final String serviceKey;
    private final ApmsSyncPlan plan;
    private final int maxRequests;
    private final int maxAnimals;
    private List<ApmsAnimal> animals;

    public ApmsAnimalSnapshot(ApmsApiClient client,
                              @Value("${apms.api.service-key}") String serviceKey,
                              @Value("#{jobExecution}") JobExecution execution,
                              @Value("${apms.sync.max-requests:100}") int maxRequests,
                              @Value("${apms.sync.max-animals:50000}") int maxAnimals) {
        this.client = client;
        this.serviceKey = serviceKey;
        this.plan = ApmsSyncPlan.from(execution.getJobParameters());
        if (maxRequests < 1 || maxAnimals < 1) {
            throw new IllegalArgumentException("APMS collection limits must be positive");
        }
        this.maxRequests = maxRequests;
        this.maxAnimals = maxAnimals;
    }

    public synchronized List<ApmsAnimal> animals() {
        if (animals != null) {
            return animals;
        }
        Map<String, ApmsAnimal> byNumber = new LinkedHashMap<>();
        int requests = 0;
        for (var query : plan.queries()) {
            int page = 1;
            while (true) {
                if (requests >= maxRequests) {
                    throw new IllegalStateException("APMS request budget exceeded");
                }
                requests++;
                ApmsPage response = ApmsPage.fetch(client, serviceKey, page, 1000, query);
                for (ApmsAnimal item : response.items()) {
                    if (item.getDesertionNo() == null || item.getDesertionNo().isBlank()) {
                        throw new IllegalStateException("APMS animal number is missing");
                    }
                    item.setUpdTm(ApmsUpdatedAt.normalize(item.getUpdTm()));
                    if (query.updatedStart() != null) {
                        LocalDateTime updated = ApmsUpdatedAt.parse(item.getUpdTm());
                        if (updated == null || updated.toLocalDate().isBefore(query.updatedStart())
                                || updated.toLocalDate().isAfter(query.updatedEnd())) {
                            throw new IllegalStateException("APMS result is outside the requested update window");
                        }
                    }
                    if (item.getHappenDt() == null || item.getHappenDt().isBlank()) {
                        throw new IllegalStateException("APMS intake date is missing");
                    }
                    LocalDate intake;
                    try {
                        intake = LocalDate.parse(item.getHappenDt(), DateTimeFormatter.BASIC_ISO_DATE);
                    } catch (RuntimeException exception) {
                        throw new IllegalStateException("APMS intake date is invalid");
                    }
                    if (intake.isBefore(query.intakeStart()) || intake.isAfter(query.intakeEnd())) {
                        throw new IllegalStateException("APMS result is outside the requested intake window");
                    }
                    byNumber.merge(item.getDesertionNo(), item, this::newest);
                    if (byNumber.size() > maxAnimals) {
                        throw new IllegalStateException("APMS animal budget exceeded");
                    }
                }
                if (response.last()) {
                    break;
                }
                page++;
            }
        }
        animals = List.copyOf(byNumber.values());
        log.info("APMS snapshot ready: requests={}, animals={}, updatedStart={}, end={}",
                requests, animals.size(), plan.updatedStart(), plan.end());
        return animals;
    }

    private ApmsAnimal newest(ApmsAnimal left, ApmsAnimal right) {
        if (left.equals(right)) {
            return left;
        }
        LocalDateTime leftTime = ApmsUpdatedAt.parse(left.getUpdTm());
        LocalDateTime rightTime = ApmsUpdatedAt.parse(right.getUpdTm());
        if (leftTime == null && rightTime != null) return right;
        if (rightTime == null && leftTime != null) return left;
        if (leftTime != null && !leftTime.equals(rightTime)) {
            return leftTime.isAfter(rightTime) ? left : right;
        }
        throw new IllegalStateException("APMS duplicate animal has conflicting data at the same update time");
    }
}
