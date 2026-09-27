package com.pawbridge.animalservice.service;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "postgresql")
public class ShelterObservationRecorder {
    private final JdbcTemplate jdbc;

    public ShelterObservationRecorder(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
    }

    @Transactional(timeout = 30)
    public int recordToday() {
        // One statement = one MVCC snapshot. Unique keys make retries/multiple replicas idempotent.
        // Database time fixes the KST boundary for both date and timestamp. Never rewrite an observation.
        return jdbc.update("""
                INSERT INTO shelter_daily_observations
                    (shelter_id, observation_date, observed_at, protected_count)
                SELECT s.id, (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date,
                       statement_timestamp(), COUNT(a.id)
                FROM shelters s LEFT JOIN animals a ON a.shelter_id = s.id AND a.status = 'PROTECT'
                GROUP BY s.id
                ON CONFLICT (shelter_id, observation_date) DO NOTHING
                """);
    }
}
