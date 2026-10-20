package com.pawbridge.backend.user.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterMembers;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** User owns the membership observation; the caller owns the shared writer transaction. */
public final class JdbcAnimalShelterMembers implements AnimalShelterMembers {
    private static final String EXISTS =
            "SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE care_reg_no=?)";
    private final JdbcTemplate jdbc;

    public JdbcAnimalShelterMembers(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public boolean exists(String careRegNo) {
        DataSource source = jdbc.getDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || source == null || !TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("An active shared shelter writer transaction is required");
        }
        // No role or deletion-pending predicate: any still-present association blocks deletion.
        return Objects.requireNonNull(jdbc.queryForObject(EXISTS, Boolean.class, careRegNo),
                "Shelter membership observation is unavailable");
    }
}
