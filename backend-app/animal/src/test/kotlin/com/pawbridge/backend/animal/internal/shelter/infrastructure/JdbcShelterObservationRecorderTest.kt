package com.pawbridge.backend.animal.internal.shelter.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class JdbcShelterObservationRecorderTest {
    @ParameterizedTest
    @ValueSource(ints = [3, 0])
    fun given__new_or_already_observed_shelters__when__recording_today__then__execute_one_original_snapshot_and_return_insert_count(
        inserted: Int,
    ) {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(any())).thenReturn(status)
        `when`(jdbc.update(anyString())).thenReturn(inserted)
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 30
        val recorder = JdbcShelterObservationRecorder(jdbc, transaction)

        assertThat(recorder.recordToday()).isEqualTo(inserted)

        val definition = ArgumentCaptor.forClass(TransactionDefinition::class.java)
        val sql = ArgumentCaptor.forClass(String::class.java)
        val order = inOrder(manager, jdbc)
        order.verify(manager).getTransaction(definition.capture())
        order.verify(jdbc).update(sql.capture())
        order.verify(manager).commit(status)
        order.verifyNoMoreInteractions()
        assertThat(definition.value.timeout).isEqualTo(30)
        assertThat(definition.value.isReadOnly).isFalse()
        assertThat(sql.value).contains(
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
    fun given__a_database_failure__when__recording_today__then__rollback_and_propagate_without_returning_success_or_retrying() {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        val failure = DataAccessResourceFailureException("synthetic-observation-unavailable")
        `when`(manager.getTransaction(any())).thenReturn(status)
        `when`(jdbc.update(anyString())).thenThrow(failure)
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 30
        val recorder = JdbcShelterObservationRecorder(jdbc, transaction)

        assertThatThrownBy { recorder.recordToday() }.isSameAs(failure)

        val order = inOrder(manager, jdbc)
        order.verify(manager).getTransaction(transaction)
        order.verify(jdbc).update(anyString())
        order.verify(manager).rollback(status)
        order.verifyNoMoreInteractions()
    }
}
