package com.pawbridge.backend.animal.internal.shelter.infrastructure

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class JdbcShelterObservationRecorderTest {
    @ParameterizedTest
    @ValueSource(ints = [3, 0])
    fun `새 보호소이거나 이미 관측된 보호소일 때__오늘을 기록하면__원래 스냅샷을 한 번 실행하고 삽입 건수를 돌려준다`(
        inserted: Int,
    ) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<PlatformTransactionManager>()
        val status = mockk<TransactionStatus>()
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 30
        val recorder = JdbcShelterObservationRecorder(jdbc, transaction)

        every { manager.getTransaction(any()) } returns status
        every { jdbc.update(any<String>()) } returns inserted
        justRun { manager.commit(any()) }

        // when
        val result = recorder.recordToday()

        // then
        val definition = slot<TransactionDefinition>()
        val sql = slot<String>()
        assertThat(result).isEqualTo(inserted)
        verifySequence {
            manager.getTransaction(capture(definition))
            jdbc.update(capture(sql))
            manager.commit(status)
        }
        assertThat(definition.captured.timeout).isEqualTo(30)
        assertThat(definition.captured.isReadOnly).isFalse()
        assertThat(sql.captured).contains(
            "INSERT INTO pawbridge_animal.shelter_daily_observations",
            "(shelter_id, observation_date, observed_at, protected_count)",
            "SELECT s.id, (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date,",
            "statement_timestamp(), COUNT(a.id)",
            "FROM pawbridge_animal.shelters s LEFT JOIN pawbridge_animal.animals a",
            "ON a.shelter_id = s.id AND a.status = 'PROTECT'",
            "GROUP BY s.id",
            "ON CONFLICT (shelter_id, observation_date) DO NOTHING",
        ).doesNotContain("DO UPDATE", "happen_date", "CURRENT_DATE", "DELETE FROM")
    }

    @Test
    fun `데이터베이스가 실패할 때__오늘을 기록하면__성공을 돌려주거나 재시도하지 않고 롤백한 뒤 예외를 전파한다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<PlatformTransactionManager>()
        val status = mockk<TransactionStatus>()
        val failure = DataAccessResourceFailureException("synthetic-observation-unavailable")
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 30
        val recorder = JdbcShelterObservationRecorder(jdbc, transaction)

        every { manager.getTransaction(any()) } returns status
        every { jdbc.update(any<String>()) } throws failure
        justRun { manager.rollback(any()) }

        // when
        val thrown = assertThatThrownBy { recorder.recordToday() }

        // then
        thrown.isSameAs(failure)
        verifySequence {
            manager.getTransaction(transaction)
            jdbc.update(any<String>())
            manager.rollback(status)
        }
    }
}
