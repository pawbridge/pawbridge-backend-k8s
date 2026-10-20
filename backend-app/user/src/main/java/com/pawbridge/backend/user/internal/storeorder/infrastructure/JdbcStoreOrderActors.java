package com.pawbridge.backend.user.internal.storeorder.infrastructure;

import com.pawbridge.backend.store.api.StoreOrderActors;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads current User state inside the caller's shared Store order transaction. */
public final class JdbcStoreOrderActors implements StoreOrderActors {
    private static final String MEMBER = "SELECT user_id FROM pawbridge_user.users WHERE user_id=?";
    private static final String LOCK_MEMBER = MEMBER + " FOR UPDATE";
    private static final String READ_MEMBER = """
            SELECT u.user_id, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
            """;
    private static final String PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)";
    private final JdbcTemplate jdbc;

    public JdbcStoreOrderActors(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public Actor currentForWrite(long verifiedUserId) { return current(verifiedUserId, true); }

    @Override
    public Actor currentForRead(long verifiedUserId) { return current(verifiedUserId, false); }

    private Actor current(long verifiedUserId, boolean write) {
        if (verifiedUserId <= 0) throw new IllegalArgumentException("Verified User identity must be positive");
        DataSource source = jdbc.getDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || source == null
                || !TransactionSynchronizationManager.hasResource(source)
                || (write && TransactionSynchronizationManager.isCurrentTransactionReadOnly())) {
            throw new IllegalStateException("An active shared Store order transaction is required");
        }
        if (!write) {
            // One statement observes existence and deletion together even under READ COMMITTED.
            List<Actor> found = jdbc.query(READ_MEMBER, (row, index) -> {
                boolean pending = row.getBoolean("pending");
                return new Actor(row.getLong("user_id"), !pending, pending);
            }, verifiedUserId);
            return found.isEmpty() ? new Actor(verifiedUserId, false, true) : found.getFirst();
        }
        List<Long> found = jdbc.query(LOCK_MEMBER,
                (row, index) -> row.getLong("user_id"), verifiedUserId);
        if (found.isEmpty()) return new Actor(verifiedUserId, false, true);
        boolean pending = Objects.requireNonNull(jdbc.queryForObject(PENDING, Boolean.class, verifiedUserId),
                "User deletion observation is unavailable");
        // Like ContactMember, availability means existence without pending contact deletion.
        // The API's active/deleted flags are derived state, not physical users table columns.
        return new Actor(found.getFirst(), !pending, pending);
    }
}
