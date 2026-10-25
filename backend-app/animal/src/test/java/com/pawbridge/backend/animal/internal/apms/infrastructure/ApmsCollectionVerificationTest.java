package com.pawbridge.backend.animal.internal.apms.infrastructure;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ApmsCollectionVerificationTest {
    @ParameterizedTest @CsvSource({"0,0","1,1","3,3"})
    void givenNoGapOrCountMismatchOnly__whenVerifying__thenKeepOriginalFinishedPolicy(int incomplete,int mismatches) {
        assertThat(new ApmsCollectionVerification().execute(contribution(incomplete,mismatches),mock(ChunkContext.class))).isEqualTo(RepeatStatus.FINISHED);
    }
    @ParameterizedTest @CsvSource({"1,0","3,1","3,2"})
    void givenAnotherUnresolvedReason__whenVerifying__thenRejectIncompleteExecution(int incomplete,int mismatches) {
        assertThatThrownBy(() -> new ApmsCollectionVerification().execute(contribution(incomplete,mismatches),mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class).hasMessage("APMS collection incomplete; inspect job execution context");
    }
    private StepContribution contribution(int incomplete,int mismatches) {
        var execution=new JobExecution(1L);execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT,incomplete);
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT,mismatches);
        return new StepContribution(new StepExecution("apmsCollectionVerificationStep",execution));
    }
}
