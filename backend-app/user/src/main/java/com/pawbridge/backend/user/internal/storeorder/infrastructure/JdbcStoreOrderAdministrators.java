package com.pawbridge.backend.user.internal.storeorder.infrastructure;

import com.pawbridge.backend.store.api.StoreOrderAdministrators;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads current DB role; neither JWT role nor a request header grants administrator authority. */
public final class JdbcStoreOrderAdministrators implements StoreOrderAdministrators {
    private static final String LOCK_MEMBER = "SELECT user_id,role FROM pawbridge_user.users WHERE user_id=? FOR UPDATE";
    private static final String READ_MEMBER = """
            SELECT u.user_id, u.role, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
            """;
    private static final String PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)";
    private final JdbcTemplate jdbc;

    public JdbcStoreOrderAdministrators(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public Actor currentForRead(long userId) { return current(userId, false); }
    @Override public Actor currentForWrite(long userId) { return current(userId, true); }

    private Actor current(long userId, boolean write) {
        if (userId <= 0) throw new IllegalArgumentException("Verified User identity must be positive");
        DataSource source = jdbc.getDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || source == null
                || !TransactionSynchronizationManager.hasResource(source)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly() == write) {
            throw new IllegalStateException("A matching shared Store administrator transaction is required");
        }
        if (!write) {
            List<Actor> found = jdbc.query(READ_MEMBER, (row, index) -> {
                boolean pending = row.getBoolean("pending");
                return new Actor(row.getLong("user_id"), !pending, pending, row.getString("role"));
            }, userId);
            return found.isEmpty() ? new Actor(userId, false, true, null) : found.getFirst();
        }
        List<Actor> found = jdbc.query(LOCK_MEMBER,
                (row, index) -> new Actor(row.getLong("user_id"), true, false, row.getString("role")), userId);
        if (found.isEmpty()) return new Actor(userId, false, true, null);
        boolean pending = Objects.requireNonNull(jdbc.queryForObject(PENDING, Boolean.class, userId),
                "User deletion observation is unavailable");
        Actor actor = found.getFirst();
        return new Actor(actor.userId(), !pending, pending, actor.role());
    }
}
