package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.repeat.RepeatStatus

class ApmsCollectionVerificationTest {
    @ParameterizedTest
    @CsvSource("0,0", "1,1", "3,3")
    fun givenNoGapOrCountMismatchOnly__whenVerifying__thenKeepOriginalFinishedPolicy(incomplete: Int, mismatches: Int) {
        val status = ApmsCollectionVerification()
            .execute(contribution(incomplete, mismatches), mock(ChunkContext::class.java))

        assertThat(status).isEqualTo(RepeatStatus.FINISHED)
    }

    @ParameterizedTest
    @CsvSource("1,0", "3,1", "3,2")
    fun givenAnotherUnresolvedReason__whenVerifying__thenRejectIncompleteExecution(incomplete: Int, mismatches: Int) {
        assertThatThrownBy {
            ApmsCollectionVerification()
                .execute(contribution(incomplete, mismatches), mock(ChunkContext::class.java))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("APMS collection incomplete; inspect job execution context")
    }

    private fun contribution(incomplete: Int, mismatches: Int): StepContribution {
        val execution = JobExecution(1L)
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, incomplete)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, mismatches)
        return StepContribution(StepExecution("apmsCollectionVerificationStep", execution))
    }
}
