package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** One observed database snapshot per shelter and KST date; never backfills or rewrites a saved observation. */
public final class JdbcShelterObservationRecorder {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcShelterObservationRecorder(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    public int recordToday() {
        return transaction.execute(status -> jdbc.update("""
                INSERT INTO pawbridge_animal.shelter_daily_observations
                    (shelter_id, observation_date, observed_at, protected_count)
                SELECT s.id, (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date,
                       statement_timestamp(), COUNT(a.id)
                FROM pawbridge_animal.shelters s LEFT JOIN pawbridge_animal.animals a
                  ON a.shelter_id = s.id AND a.status = 'PROTECT'
                GROUP BY s.id
                ON CONFLICT (shelter_id, observation_date) DO NOTHING
                """));
    }
}
