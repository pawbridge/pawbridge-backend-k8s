package com.pawbridge.backend.user.internal.animalcommand.infrastructure;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves the verified principal while the caller owns the shared writer transaction. */
public final class JdbcAnimalCommandActors implements AnimalCommandActors {
    private static final String MEMBER = "SELECT user_id,role,care_reg_no FROM pawbridge_user.users WHERE user_id=? FOR UPDATE";
    private static final String PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)";
    private final JdbcTemplate jdbc;

    public JdbcAnimalCommandActors(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public Actor find(long verifiedUserId) {
        if (verifiedUserId <= 0) throw new IllegalArgumentException("Verified User identity must be positive");
        DataSource source = jdbc.getDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || source == null || !TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("An active shared writer transaction is required");
        }
        List<Actor> found = jdbc.query(MEMBER, (row, index) -> new Actor(row.getLong("user_id"),
                row.getString("role"), row.getString("care_reg_no"), true, false), verifiedUserId);
        if (found.isEmpty()) return new Actor(verifiedUserId, null, null, false, true);
        Actor actor = found.getFirst();
        boolean pending = Objects.requireNonNull(jdbc.queryForObject(PENDING, Boolean.class, verifiedUserId),
                "User deletion observation is unavailable");
        // As in ContactMember, active is existence without pending deletion, not a users table column.
        // Animal owns the authorization decision for the mechanically mapped role and shelter identity.
        return new Actor(actor.userId(), actor.role(), actor.careRegNo(), !pending, pending);
    }
}
