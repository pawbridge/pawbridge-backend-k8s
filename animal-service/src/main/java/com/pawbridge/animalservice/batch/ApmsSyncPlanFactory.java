package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.repository.AnimalRepository;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class ApmsSyncPlanFactory {
    private final AnimalRepository animals;
    private final JobExplorer explorer;
    private final Clock clock;

    public ApmsSyncPlanFactory(AnimalRepository animals, JobExplorer explorer,
                               @Qualifier("apmsClock") Clock clock) {
        this.animals = animals;
        this.explorer = explorer;
        this.clock = clock;
    }

    public ApmsSyncPlan create(String jobName) {
        LocalDate today = LocalDate.now(clock);
        LocalDate recent = today.minusDays(30);
        LocalDate oldest = animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL);
        LocalDate history = oldest != null && oldest.isBefore(recent) ? oldest : recent;
        var prior = history(jobName, today);
        LocalDate updated = earlier(prior.recovery(), recent);
        if (updated.isBefore(history)) history = updated;
        Map<LocalDate, LocalDate> monthly = new TreeMap<>();
        LocalDate intake = recent;
        // Failed attempts also preserve the unfiltered intake window (animals may have no updTm).
        for (var attempt : prior.attempts()) {
            history = earlier(attempt.plan().historyStart(), history);
            intake = earlier(attempt.plan().recentStart(), intake);
        }
        for (var attempt : prior.attempts()) {
            var results = ApmsQueryProgress.verifiedResults(attempt.execution(), attempt.plan());
            var queries = attempt.plan().queries();
            for (int i = 0; i < queries.size(); i++) {
                var query = queries.get(i);
                boolean complete = !results.isEmpty() && results.get(i).complete();
                if (query.updatedStart() == null) {
                    if (!complete) intake = earlier(intake, query.intakeStart());
                    else if (!query.intakeStart().isAfter(intake) && query.intakeEnd().isAfter(intake)) intake = query.intakeEnd();
                } else {
                    var checkpoint = monthly.getOrDefault(query.intakeStart(), updated);
                    // An incomplete overlap can contain delayed source records, even before a prior success.
                    if (!complete) monthly.put(query.intakeStart(), earlier(checkpoint, query.updatedStart()));
                    // A later narrow window cannot fill an earlier unresolved gap.
                    else if (!query.updatedStart().isAfter(checkpoint) && query.updatedEnd().isAfter(checkpoint)) {
                        monthly.put(query.intakeStart(), query.updatedEnd());
                    }
                }
            }
        }
        monthly.replaceAll((month, through) -> earlier(through, recent));
        for (LocalDate start : monthly.values()) updated = earlier(updated, start);
        history = earlier(updated, history);
        return new ApmsSyncPlan(earlier(intake, recent), history.withDayOfMonth(1), updated, today, monthly);
    }

    private History history(String jobName, LocalDate today) {
        LocalDate firstAttempt = null;
        List<Attempt> attempts = new ArrayList<>();
        int offset = 0;
        while (true) {
            var instances = explorer.getJobInstances(jobName, offset, 100);
            if (instances.isEmpty()) break;
            for (var instance : instances) {
                var executions = new ArrayList<>(explorer.getJobExecutions(instance));
                executions.sort(Comparator.comparing(JobExecution::getId).reversed());
                for (var execution : executions) {
                    String contract = execution.getJobParameters().getString(ApmsSyncPlan.CONTRACT_KEY);
                    if (!ApmsSyncPlan.CONTRACT.equals(contract) && !ApmsSyncPlan.LEGACY_CONTRACT.equals(contract)) continue;
                    var plan = ApmsSyncPlan.from(execution.getJobParameters());
                    if (plan.end().isAfter(today)) throw new IllegalStateException("APMS recovery window is in the future");
                    if (successful(execution, plan, contract)) {
                        attempts.sort(Comparator.comparing(attempt -> attempt.execution().getId()));
                        return new History(plan.end(), List.copyOf(attempts));
                    }
                    firstAttempt = earlier(plan.updatedStart(), firstAttempt);
                    attempts.add(new Attempt(execution, plan));
                }
            }
            offset += instances.size();
        }
        attempts.sort(Comparator.comparing(attempt -> attempt.execution().getId()));
        return new History(firstAttempt, List.copyOf(attempts));
    }

    private boolean successful(JobExecution execution, ApmsSyncPlan plan, String contract) {
        if (execution.getStatus() != BatchStatus.COMPLETED
                || execution.getStepExecutions().stream().anyMatch(step -> step.getSkipCount() != 0)) return false;
        // The older contract has no per-query evidence. Retain its existing full-success fallback.
        return ApmsSyncPlan.LEGACY_CONTRACT.equals(contract)
                || !ApmsQueryProgress.verifiedResults(execution, plan).isEmpty();
    }

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        if (left == null) return right;
        return right == null || left.isBefore(right) ? left : right;
    }

    private record Attempt(JobExecution execution, ApmsSyncPlan plan) { }
    private record History(LocalDate recovery, List<Attempt> attempts) { }
}
