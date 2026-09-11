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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.time.temporal.ChronoUnit;
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
    public static final String INCOMPLETE_COUNT = "apms.sync.incompleteQueryCount";
    public static final String INCOMPLETE_DETAILS = "apms.sync.incompleteQueryDetails";
    public static final String COLLECTED_COUNT = "apms.sync.collectedCount";
    private static final int MAX_SPLIT_DEPTH = 2;
    private static final int RECOVERY_REQUESTS_PER_QUERY = 8;
    private final JobExecution execution;
    private int requests;
    private List<ApmsAnimal> animals;

    public ApmsAnimalSnapshot(ApmsApiClient client,
                              @Value("${apms.api.service-key}") String serviceKey,
                              @Value("#{jobExecution}") JobExecution execution,
                              @Value("${apms.sync.max-requests:100}") int maxRequests,
                              @Value("${apms.sync.max-animals:50000}") int maxAnimals) {
        this.execution = execution;
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
        List<String> incomplete = new ArrayList<>();
        List<ApmsQueryProgress.Result> results = new ArrayList<>();
        // Complete the first pass before spending retries on a problematic interval.
        // A bad early month must not consume the request budget of later healthy months.
        Map<ApmsSyncPlan.Query, Outcome> initial = new LinkedHashMap<>();
        for (var query : plan.queries()) {
            initial.put(query, scan(query, byNumber, new RecoveryBudget(Integer.MAX_VALUE)));
        }
        for (var entry : initial.entrySet()) {
            var query = entry.getKey();
            Outcome result = entry.getValue();
            if (!result.complete()) {
                result = recover(query, result, 0, new RecoveryBudget(RECOVERY_REQUESTS_PER_QUERY), byNumber);
            }
            results.add(new ApmsQueryProgress.Result(query, result.complete()));
            if (!result.complete()) {
                incomplete.add("intake=" + query.intakeStart() + ".." + query.intakeEnd()
                        + ";updated=" + query.updatedStart() + ".." + query.updatedEnd()
                        + ";reason=" + result.reason() + ";reported=" + result.expected()
                        + ";observed=" + result.numbers().size());
            }
        }
        ApmsQueryProgress.store(execution, results);
        animals = List.copyOf(byNumber.values());
        execution.getExecutionContext().putInt(INCOMPLETE_COUNT, incomplete.size());
        execution.getExecutionContext().putString(INCOMPLETE_DETAILS, String.join("\n", incomplete));
        execution.getExecutionContext().putInt(COLLECTED_COUNT, animals.size());
        log.info("APMS snapshot ready: requests={}, animals={}, incompleteQueries={}, updatedStart={}, end={}",
                requests, animals.size(), incomplete.size(), plan.updatedStart(), plan.end());
        return animals;
    }

    private Outcome recover(ApmsSyncPlan.Query query, Outcome first, int depth, RecoveryBudget budget,
                            Map<String, ApmsAnimal> byNumber) {
        Outcome retry = scan(query, byNumber, budget);
        Set<String> observed = new HashSet<>(first.numbers());
        observed.addAll(retry.numbers());
        long expected = Math.max(first.expected(), retry.expected());
        if (retry.complete() && retry.expected() >= expected && retry.numbers().containsAll(observed)) {
            return retry;
        }
        String reason = first.reason();
        long days = ChronoUnit.DAYS.between(query.intakeStart(), query.intakeEnd());
        if (depth < MAX_SPLIT_DEPTH && days > 0 && budget.remaining > 0 && requests < maxRequests) {
            LocalDate middle = query.intakeStart().plusDays(days / 2);
            var leftQuery = new ApmsSyncPlan.Query(query.intakeStart(), middle, query.updatedStart(), query.updatedEnd());
            var rightQuery = new ApmsSyncPlan.Query(middle.plusDays(1), query.intakeEnd(), query.updatedStart(), query.updatedEnd());
            Outcome left = scan(leftQuery, byNumber, budget);
            if (!left.complete()) left = recover(leftQuery, left, depth + 1, budget, byNumber);
            Outcome right = scan(rightQuery, byNumber, budget);
            if (!right.complete()) right = recover(rightQuery, right, depth + 1, budget, byNumber);
            Set<String> children = new HashSet<>(left.numbers());
            children.addAll(right.numbers());
            boolean reconciled = left.complete() && right.complete()
                    && children.size() == left.expected() + right.expected()
                    && children.size() >= expected && children.containsAll(observed);
            observed.addAll(children);
            if (reconciled) return new Outcome(true, children.size(), children, "");
        }
        return new Outcome(false, expected, observed, reason);
    }

    private Outcome scan(ApmsSyncPlan.Query query, Map<String, ApmsAnimal> byNumber, RecoveryBudget budget) {
        Set<String> numbers = new HashSet<>();
        long total = -1;
        boolean consistent = true;
        for (int page = 1; ; page++) {
            if (requests >= maxRequests || budget.remaining <= 0) {
                return new Outcome(false, Math.max(0, total), numbers, "REQUEST_BUDGET");
            }
            requests++;
            budget.remaining--;
            ApmsPage response;
            try {
                response = ApmsPage.fetch(client, serviceKey, page, 1000, query);
            } catch (ApmsPage.FetchException exception) {
                return new Outcome(false, Math.max(0, total), numbers, exception.getMessage());
            }
            if (total < 0) total = response.total();
            consistent &= response.total() == total && response.countMatches();
            total = Math.max(total, response.total());
            for (ApmsAnimal item : response.items()) {
                validate(item, query);
                consistent &= numbers.add(item.getDesertionNo());
                if (!byNumber.containsKey(item.getDesertionNo()) && byNumber.size() >= maxAnimals) {
                    return new Outcome(false, total, numbers, "ANIMAL_BUDGET");
                }
                byNumber.merge(item.getDesertionNo(), item, this::newest);
            }
            if (response.last() || response.items().isEmpty() || !consistent) {
                boolean complete = consistent && numbers.size() == total;
                return new Outcome(complete, total, numbers, complete ? "" : "COUNT_MISMATCH");
            }
        }
    }

    private void validate(ApmsAnimal item, ApmsSyncPlan.Query query) {
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
    }

    private record Outcome(boolean complete, long expected, Set<String> numbers, String reason) { }

    private static class RecoveryBudget {
        private int remaining;
        private RecoveryBudget(int remaining) { this.remaining = remaining; }
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
