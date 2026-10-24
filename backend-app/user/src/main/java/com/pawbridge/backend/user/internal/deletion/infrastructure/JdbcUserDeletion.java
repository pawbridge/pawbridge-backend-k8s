package com.pawbridge.backend.user.internal.deletion.infrastructure;

import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException;
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker;
import java.util.function.LongConsumer;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL coordination; the caller must not add a transaction around the whole operation. */
public final class JdbcUserDeletion implements UserDeletion {
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final LongConsumer communityCleanup;
    private final UserSessionRevoker sessions;

    public JdbcUserDeletion(JdbcOperations jdbc, TransactionOperations transactions, LongConsumer communityCleanup) {
        this(jdbc, transactions, communityCleanup, UserSessionRevoker.NONE);
    }

    public JdbcUserDeletion(JdbcOperations jdbc, TransactionOperations transactions, LongConsumer communityCleanup,
            UserSessionRevoker sessions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.communityCleanup = communityCleanup;
        this.sessions = sessions;
    }

    @Override
    public void delete(long userId) {
        transactions.executeWithoutResult(status -> {
            var users = jdbc.query("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE",
                    (row, rowNumber) -> row.getLong(1), userId);
            if (users.isEmpty()) throw new UserNotFoundException();
            jdbc.update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
            // A member pending deletion must not refresh while the community cleanup runs or awaits a retry.
            sessions.revokeAll(userId);
        });
        try {
            communityCleanup.accept(userId);
        } catch (RuntimeException failure) {
            // The committed pending marker is retained for an authorized retry, as in the source.
            throw new MemberCleanupUnavailableException();
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=?", userId);
            jdbc.update("DELETE FROM pawbridge_user.shelter_applications WHERE user_id=?", userId);
            // Preserve deleteById: missing at lookup is a no-op, disappearance after lookup is stale.
            var users = jdbc.query("SELECT user_id FROM pawbridge_user.users WHERE user_id=?",
                    (row, rowNumber) -> row.getLong(1), userId);
            if (!users.isEmpty() && jdbc.update("DELETE FROM pawbridge_user.users WHERE user_id=?", userId) != 1) {
                throw new OptimisticLockingFailureException("Account disappeared during deletion");
            }
        });
    }
}
