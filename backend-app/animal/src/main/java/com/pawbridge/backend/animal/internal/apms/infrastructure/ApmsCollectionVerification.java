package com.pawbridge.backend.animal.internal.apms.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** Original apmsCollectionVerificationStep result policy; Boot preserves the original step name. */
@Slf4j
public final class ApmsCollectionVerification implements Tasklet {
    @Override public RepeatStatus execute(StepContribution contribution, ChunkContext context) {
        var execution = contribution.getStepExecution().getJobExecution();
        int incomplete = execution.getExecutionContext()
                .getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0);
        int countMismatches = execution.getExecutionContext()
                .getInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 0);
        if (incomplete > 0 && incomplete == countMismatches) {
            log.warn("[BATCH] APMS count mismatch remains retryable: queries={}", incomplete);
            return RepeatStatus.FINISHED;
        }
        if (incomplete > 0) {
            throw new IllegalStateException("APMS collection incomplete; inspect job execution context");
        }
        return RepeatStatus.FINISHED;
    }
}
