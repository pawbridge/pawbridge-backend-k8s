package com.pawbridge.animalservice.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.util.List;

/** Collection evidence only. A query becomes a checkpoint after ingestion and indexing succeed. */
public final class ApmsQueryProgress {
    public static final String CONTEXT_KEY = "apms.sync.queryResults";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final List<String> DATA_STEPS = List.of("shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep");

    private ApmsQueryProgress() { }

    public record Result(ApmsSyncPlan.Query query, boolean complete) { }
    public record Evidence(int version, List<Result> results) { }

    public static void store(JobExecution execution, List<Result> results) {
        try {
            execution.getExecutionContext().putString(CONTEXT_KEY, JSON.writeValueAsString(new Evidence(1, results)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize APMS query results", exception);
        }
    }

    public static List<Result> verifiedResults(JobExecution execution, ApmsSyncPlan plan) {
        if (execution.getStatus() != BatchStatus.COMPLETED && execution.getStatus() != BatchStatus.FAILED) return List.of();
        if (execution.getStepExecutions().size() != DATA_STEPS.size() + 1) return List.of();
        if (execution.getStepExecutions().stream().anyMatch(step -> step.getSkipCount() != 0)) return List.of();
        for (String name : DATA_STEPS) {
            var steps = execution.getStepExecutions().stream().filter(step -> name.equals(step.getStepName())).toList();
            if (steps.size() != 1 || steps.get(0).getStatus() != BatchStatus.COMPLETED) return List.of();
        }
        var verification = execution.getStepExecutions().stream()
                .filter(step -> "apmsCollectionVerificationStep".equals(step.getStepName())).toList();
        if (verification.size() != 1 || verification.get(0).getStatus() != execution.getStatus()) return List.of();
        // Unknown or malformed evidence never advances recovery. The original plan remains retryable.
        try {
            var context = execution.getExecutionContext();
            var evidence = JSON.readValue(context.getString(CONTEXT_KEY), Evidence.class);
            if (evidence.version() != 1 || evidence.results() == null
                    || !evidence.results().stream().map(Result::query).toList().equals(plan.queries())) return List.of();
            long incomplete = evidence.results().stream().filter(result -> !result.complete()).count();
            if (incomplete != context.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)
                    || (incomplete == 0) != (execution.getStatus() == BatchStatus.COMPLETED)) return List.of();
            return List.copyOf(evidence.results());
        } catch (RuntimeException | JsonProcessingException exception) {
            return List.of();
        }
    }
}
