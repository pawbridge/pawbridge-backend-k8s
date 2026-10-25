package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus

/** Original apmsCollectionVerificationStep result policy; Boot preserves the original step name. */
class ApmsCollectionVerification : Tasklet {

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val execution = contribution.stepExecution.jobExecution
        val incomplete = execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0)
        val countMismatches = execution.executionContext.getInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 0)
        if (incomplete > 0 && incomplete == countMismatches) {
            log.warn("[BATCH] APMS count mismatch remains retryable: queries={}", incomplete)
            return RepeatStatus.FINISHED
        }
        if (incomplete > 0) {
            throw IllegalStateException("APMS collection incomplete; inspect job execution context")
        }
        return RepeatStatus.FINISHED
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ApmsCollectionVerification::class.java)
    }
}
