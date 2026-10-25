package com.pawbridge.backend.animal.internal.apms.infrastructure

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.repeat.RepeatStatus

class ApmsCollectionVerificationTest {
    @ParameterizedTest
    @CsvSource("0,0", "1,1", "3,3")
    fun `미완료 건수가 모두 건수 불일치로 설명될 때__검증하면__기존 완료 정책대로 끝낸다`(incomplete: Int, mismatches: Int) {
        // given
        val contribution = contribution(incomplete, mismatches)
        val chunkContext = mockk<ChunkContext>()

        // when
        val status = ApmsCollectionVerification().execute(contribution, chunkContext)

        // then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED)
    }

    @ParameterizedTest
    @CsvSource("1,0", "3,1", "3,2")
    fun `건수 불일치로 설명되지 않는 미해결 사유가 있을 때__검증하면__불완전한 실행을 거부한다`(incomplete: Int, mismatches: Int) {
        // given
        val contribution = contribution(incomplete, mismatches)
        val chunkContext = mockk<ChunkContext>()

        // when
        val thrown = assertThatThrownBy { ApmsCollectionVerification().execute(contribution, chunkContext) }

        // then
        thrown
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
