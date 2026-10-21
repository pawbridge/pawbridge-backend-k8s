package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcShelterObservationRecorderTest {
    @ParameterizedTest
    @ValueSource(ints = {3, 0})
    void given__new_or_already_observed_shelters__when__recording_today__then__execute_one_original_snapshot_and_return_insert_count(int inserted) {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        when(jdbc.update(anyString())).thenReturn(inserted);
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
        var recorder = new JdbcShelterObservationRecorder(jdbc, transaction);

        assertThat(recorder.recordToday()).isEqualTo(inserted);

        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        var sql = ArgumentCaptor.forClass(String.class);
        var order = inOrder(manager, jdbc);
        order.verify(manager).getTransaction(definition.capture());
        order.verify(jdbc).update(sql.capture());
        order.verify(manager).commit(status);
        order.verifyNoMoreInteractions();
        assertThat(definition.getValue().getTimeout()).isEqualTo(30);
        assertThat(definition.getValue().isReadOnly()).isFalse();
        assertThat(sql.getValue()).contains("INSERT INTO pawbridge_animal.shelter_daily_observations",
                "(shelter_id, observation_date, observed_at, protected_count)",
                "SELECT s.id, (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date,",
                "statement_timestamp(), COUNT(a.id)",
                "FROM pawbridge_animal.shelters s LEFT JOIN pawbridge_animal.animals a",
                "ON a.shelter_id = s.id AND a.status = 'PROTECT'", "GROUP BY s.id",
                "ON CONFLICT (shelter_id, observation_date) DO NOTHING")
                .doesNotContain("DO UPDATE", "happen_date", "CURRENT_DATE", "DELETE FROM");
    }

    @Test
    void given__a_database_failure__when__recording_today__then__rollback_and_propagate_without_returning_success_or_retrying() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        var failure = new DataAccessResourceFailureException("synthetic-observation-unavailable");
        when(manager.getTransaction(any())).thenReturn(status);
        when(jdbc.update(anyString())).thenThrow(failure);
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
        var recorder = new JdbcShelterObservationRecorder(jdbc, transaction);

        assertThatThrownBy(recorder::recordToday).isSameAs(failure);

        var order = inOrder(manager, jdbc);
        order.verify(manager).getTransaction(transaction);
        order.verify(jdbc).update(anyString());
        order.verify(manager).rollback(status);
        order.verifyNoMoreInteractions();
    }
}
