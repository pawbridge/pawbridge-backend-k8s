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
        LocalDate recovery = recoveryStart(jobName);
        if (recovery != null && recovery.isAfter(today)) {
            throw new IllegalStateException("APMS recovery window is in the future");
        }
        // Overlap the whole last successful day; failed executions never advance this date.
        LocalDate updated = recovery != null && recovery.isBefore(recent) ? recovery : recent;
        // Include intakes missed during an outage even when the database is still empty.
        if (updated.isBefore(history)) history = updated;
        return new ApmsSyncPlan(recent, history.withDayOfMonth(1), updated, today);
    }

    private LocalDate recoveryStart(String jobName) {
        LocalDate firstAttempt = null;
        int offset = 0;
        while (true) {
            var instances = explorer.getJobInstances(jobName, offset, 100);
            if (instances.isEmpty()) {
                return firstAttempt;
            }
            for (var instance : instances) {
                var executions = explorer.getJobExecutions(instance);
                // Until the first success, preserve the first attempted window across long outages.
                for (var execution : executions) {
                    if (ApmsSyncPlan.CONTRACT.equals(execution.getJobParameters().getString(ApmsSyncPlan.CONTRACT_KEY))) {
                        LocalDate attempted = ApmsSyncPlan.from(execution.getJobParameters()).updatedStart();
                        if (firstAttempt == null || attempted.isBefore(firstAttempt)) firstAttempt = attempted;
                    }
                }
                LocalDate completed = executions.stream()
                        .filter(this::successfulUpdatedContract)
                        .map(execution -> LocalDate.parse(execution.getJobParameters().getString(ApmsSyncPlan.END_KEY)))
                        .max(LocalDate::compareTo).orElse(null);
                if (completed != null) {
                    return completed;
                }
            }
            offset += instances.size();
        }
    }

    private boolean successfulUpdatedContract(JobExecution execution) {
        return execution.getStatus() == BatchStatus.COMPLETED
                && ApmsSyncPlan.CONTRACT.equals(execution.getJobParameters().getString(ApmsSyncPlan.CONTRACT_KEY))
                && execution.getStepExecutions().stream().allMatch(step -> step.getSkipCount() == 0);
    }
}
